// SPDX-FileCopyrightText: 2024 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

use std::{
    collections::BTreeMap,
    ffi::{OsStr, OsString},
    io::{self, IoSlice, Read, Write},
    os::{
        fd::{AsRawFd, BorrowedFd},
        unix::ffi::OsStringExt,
    },
    path::{Path, PathBuf},
};

use anyhow::{anyhow, bail, Context, Result};
use cap_std::{ambient_authority, fs::Dir};
use rustix::{
    fs::{AtFlags, Gid, Uid},
    io::Errno,
};

use crate::util;

fn open_configfs_dir(path: &Path) -> Result<Dir> {
    Dir::open_ambient_dir(path, ambient_authority())
        .and_then(|d| util::check_fs_magic(d, util::CONFIGFS_MAGIC))
        .with_context(|| format!("Failed to open directory: {path:?}"))
}

fn open_configfs_rel_dir(dir_path: &Path, dir: &Dir, path: &Path) -> Result<Dir> {
    dir.open_dir(path)
        .and_then(|d| util::check_fs_magic(d, util::CONFIGFS_MAGIC))
        .with_context(|| format!("Failed to open directory: {:?}", dir_path.join(path)))
}

fn read_configfs_file(dir_path: &Path, dir: &Dir, path: &Path) -> Result<Vec<u8>> {
    || -> Result<Vec<u8>> {
        let mut file = dir
            .open(path)
            .and_then(|d| util::check_fs_magic(d, util::CONFIGFS_MAGIC))?;

        let mut buf = vec![];
        file.read_to_end(&mut buf)?;

        Ok(buf)
    }()
    .with_context(|| format!("Failed to read file: {:?}", dir_path.join(path)))
}

fn write_configfs_file(dir_path: &Path, dir: &Dir, path: &Path, bufs: &[IoSlice]) -> Result<()> {
    || -> Result<()> {
        let mut file = dir
            .create(path)
            .and_then(|d| util::check_fs_magic(d, util::CONFIGFS_MAGIC))?;

        let n = file.write_vectored(bufs)?;
        if n != bufs.iter().map(|b| b.len()).sum() {
            bail!("Failed to write data in single syscall");
        }

        Ok(())
    }()
    .with_context(|| format!("Failed to write file: {:?}", dir_path.join(path)))
}

fn recursive_chown_configfs_dir(
    dir_path: &Path,
    dir: &Dir,
    uid: Option<Uid>,
    gid: Option<Gid>,
) -> Result<()> {
    rustix::fs::chownat(
        dir,
        "",
        uid,
        gid,
        AtFlags::EMPTY_PATH | AtFlags::SYMLINK_NOFOLLOW,
    )
    .with_context(|| format!("Failed to chown file: {dir_path:?}"))?;

    for entry in dir
        .entries()
        .with_context(|| format!("Failed to read directory: {dir_path:?}"))?
    {
        let entry =
            entry.with_context(|| format!("Failed to read directory entry: {dir_path:?}"))?;
        let child_name = entry.file_name();
        let child_type = entry.file_type().with_context(|| {
            format!("Failed to get file type: {:?}", dir_path.join(&child_name))
        })?;

        if child_type.is_dir() {
            let child_dir = open_configfs_rel_dir(dir_path, dir, Path::new(&child_name))?;
            recursive_chown_configfs_dir(&dir_path.join(&child_name), &child_dir, uid, gid)?;
        } else {
            rustix::fs::chownat(
                dir,
                &child_name,
                uid,
                gid,
                AtFlags::EMPTY_PATH | AtFlags::SYMLINK_NOFOLLOW,
            )
            .with_context(|| format!("Failed to chown file: {:?}", dir_path.join(&child_name)))?;
        }
    }

    Ok(())
}

fn chown_configfs_dir_to_rugid(dir_path: &Path, dir: &Dir, path: &Path) -> Result<()> {
    let ruid = rustix::process::getuid();
    let rgid = rustix::process::getgid();

    let child_dir = open_configfs_rel_dir(dir_path, dir, path)?;
    let child_path = dir_path.join(path);

    recursive_chown_configfs_dir(&child_path, &child_dir, Some(ruid), Some(rgid))?;

    Ok(())
}

/// Configure a USB gadget via configfs.
pub struct UsbGadget {
    root: PathBuf,
    config_name: OsString,
    dir: Dir,
}

impl UsbGadget {
    pub fn new(root: impl Into<PathBuf>, config_name: impl Into<OsString>) -> Result<Self> {
        let root = root.into();
        let config_name = config_name.into();

        let (Some(parent_path), Some(name)) = (root.parent(), root.file_name()) else {
            bail!("Failed to split path: {root:?}");
        };

        let name = Path::new(name);
        let parent = open_configfs_dir(parent_path)?;

        // Older devices without the gadget HAL might leave the files owned by
        // root because the USB config switching is done by init scripts that
        // have root privileges.
        chown_configfs_dir_to_rugid(parent_path, &parent, name)?;

        let dir = open_configfs_rel_dir(parent_path, &parent, name)?;

        Ok(Self {
            root,
            config_name,
            dir,
        })
    }

    fn configs_rel_path(&self) -> PathBuf {
        Path::new("configs").join(&self.config_name)
    }

    fn functions_rel_path(&self) -> &'static Path {
        Path::new("functions")
    }

    fn function_rel_path(&self, name: &OsStr) -> PathBuf {
        Path::new("functions").join(name)
    }

    fn open_dir(&self, rel_path: &Path) -> Result<(PathBuf, Dir)> {
        let dir = open_configfs_rel_dir(&self.root, &self.dir, rel_path)?;

        Ok((self.root.join(rel_path), dir))
    }

    /// Associate or disassociate this gadget configuration with a USB
    /// controller. This function is idempotent.
    pub fn set_controller(&self, id: Option<&str>) -> Result<()> {
        if let Err(e) = write_configfs_file(
            &self.root,
            &self.dir,
            Path::new("UDC"),
            &[
                IoSlice::new(id.map(|s| s.as_bytes()).unwrap_or_default()),
                IoSlice::new(b"\n"),
            ],
        ) {
            // If there are no active configs, this will fail with ENODEV.
            if id.is_some()
                || e.downcast_ref::<io::Error>().and_then(|e| e.raw_os_error())
                    != Some(Errno::NODEV.raw_os_error())
            {
                return Err(e);
            }
        }

        Ok(())
    }

    /// Get the list of active gadget functions in the config.
    pub fn configs(&self) -> Result<BTreeMap<OsString, OsString>> {
        let (path, dir) = self.open_dir(&self.configs_rel_path())?;

        let mut functions = BTreeMap::new();

        for entry in dir
            .entries()
            .with_context(|| format!("Failed to read directory: {path:?}"))?
        {
            let entry =
                entry.with_context(|| format!("Failed to read directory entry: {path:?}"))?;

            if !entry.file_type().map(|t| t.is_symlink()).unwrap_or(false) {
                continue;
            }

            let target = match dir.read_link_contents(entry.file_name()) {
                Ok(t) => t,
                Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
                Err(e) => {
                    return Err(e).with_context(|| {
                        format!("Failed to read link: {:?}", path.join(entry.file_name()))
                    })
                }
            };

            let target_name = target
                .file_name()
                .ok_or_else(|| anyhow!("Failed to parse file name: {target:?}"))?;

            functions.insert(entry.file_name(), target_name.to_owned());
        }

        Ok(functions)
    }

    /// Add a gadget function to the config. Returns whether the config entry
    /// is newly created. This function will fail if the config entry exists,
    /// but points to a different gadget function.
    pub fn create_config(&self, name: &OsStr, function: &OsStr) -> Result<bool> {
        // We can't use a relative path here. The kernel resolves it immediately
        // relative to the cwd.
        let function_path = self.root.join(self.function_rel_path(function));
        let (path, dir) = self.open_dir(&self.configs_rel_path())?;

        match dir.symlink_contents(function_path, name) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                let target = dir.read_link_contents(name);
                if target.as_ref().ok().and_then(|t| t.file_name()) == Some(function) {
                    Ok(false)
                } else {
                    Err(e)
                }
            }
            Err(e) => Err(e),
        }
        .with_context(|| format!("Failed to create config: {:?}", path.join(name)))
    }

    /// Remove a config entry. Returns whether the config entry previously
    /// existed.
    pub fn delete_config(&self, name: &OsStr) -> Result<bool> {
        let (path, dir) = self.open_dir(&self.configs_rel_path())?;

        match dir.remove_file(name) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(e) => {
                Err(e).with_context(|| format!("Failed to delete config: {:?}", path.join(name)))
            }
        }
    }

    /// Create a gadget function. Returns whether the function is newly created.
    pub fn create_function(&self, name: &OsStr) -> Result<bool> {
        let (path, dir) = self.open_dir(self.functions_rel_path())?;

        match dir.create_dir(name) {
            Ok(_) => {
                chown_configfs_dir_to_rugid(&path, &dir, Path::new(name))?;
                Ok(true)
            }
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => Ok(false),
            Err(e) => {
                Err(e).with_context(|| format!("Failed to create function: {:?}", path.join(name)))
            }
        }
    }

    /// Delete a gadget function. Returns whether the function previously
    /// existed.
    pub fn delete_function(&self, name: &OsStr) -> Result<bool> {
        let (path, dir) = self.open_dir(self.functions_rel_path())?;

        match dir.remove_dir(name) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(e) => {
                Err(e).with_context(|| format!("Failed to delete function: {:?}", path.join(name)))
            }
        }
    }

    /// Open an existing mass storage function. Returns None if the function
    /// does not exist.
    pub fn open_mass_storage_function(&self, name: &OsStr) -> Result<Option<MassStorageFunction>> {
        match self.open_dir(&self.function_rel_path(name)) {
            Ok((p, d)) => Ok(Some(MassStorageFunction::new(p, d))),
            Err(e)
                if e.downcast_ref::<io::Error>().map(|ie| ie.kind())
                    == Some(io::ErrorKind::NotFound) =>
            {
                Ok(None)
            }
            Err(e) => Err(e),
        }
    }
}

/// Configure a mass storage USB gadget function.
pub struct MassStorageFunction {
    path: PathBuf,
    dir: Dir,
}

impl MassStorageFunction {
    fn new(path: PathBuf, dir: Dir) -> Self {
        Self { path, dir }
    }

    /// Get the list of LUNs. The LUNs may or may not have associated files.
    pub fn luns(&self) -> Result<Vec<u8>> {
        let mut result = vec![];

        for entry in self
            .dir
            .entries()
            .with_context(|| format!("Failed to read directory: {:?}", self.path))?
        {
            let entry = entry
                .with_context(|| format!("Failed to read directory entry: {:?}", self.path))?;

            let name = entry.file_name();
            let Some(name) = name.to_str() else {
                continue;
            };

            let Some(suffix) = name.strip_prefix("lun.") else {
                continue;
            };

            let Ok(n) = suffix.parse::<u8>() else {
                continue;
            };

            result.push(n);
        }

        Ok(result)
    }

    /// Create a LUN. Returns whether the LUN is newly created.
    pub fn create_lun(&self, lun: u8) -> Result<bool> {
        let name = format!("lun.{lun}");

        match self.dir.create_dir(&name) {
            Ok(_) => {
                chown_configfs_dir_to_rugid(&self.path, &self.dir, Path::new(&name))?;
                Ok(true)
            }
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => Ok(false),
            Err(e) => {
                Err(e).with_context(|| format!("Failed to create LUN: {:?}", self.path.join(name)))
            }
        }
    }

    /// Delete a LUN. Returns whether the LUN previously existed.
    pub fn delete_lun(&self, lun: u8) -> Result<bool> {
        let name = format!("lun.{lun}");

        match self.dir.remove_dir(&name) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(e) => {
                Err(e).with_context(|| format!("Failed to delete LUN: {:?}", self.path.join(name)))
            }
        }
    }

    /// Get the configuration for an existing LUN. Returns the path, whether the
    /// device is a CDROM, and whether the device is read-only.
    pub fn get_lun(&self, lun: u8) -> Result<(PathBuf, bool, bool)> {
        let name = format!("lun.{lun}");
        let path = Path::new(&name);

        let mut file = read_configfs_file(&self.path, &self.dir, &path.join("file"))?;
        let mut cdrom = read_configfs_file(&self.path, &self.dir, &path.join("cdrom"))?;
        let mut ro = read_configfs_file(&self.path, &self.dir, &path.join("ro"))?;

        fn pop_newline(data: &mut Vec<u8>) -> Result<()> {
            match data.pop() {
                Some(b'\n') => return Ok(()),
                Some(b) => data.push(b),
                None => {}
            }

            bail!("configfs file did not end in newline: {data:?}");
        }

        pop_newline(&mut file)?;
        pop_newline(&mut cdrom)?;
        pop_newline(&mut ro)?;

        fn get_bool(data: &[u8]) -> Result<bool> {
            match data {
                b"1" => Ok(true),
                b"0" => Ok(false),
                _ => bail!("configfs file did not contain boolean: {data:?}"),
            }
        }

        let cdrom = get_bool(&cdrom)?;
        let ro = get_bool(&ro)?;

        Ok((PathBuf::from(OsString::from_vec(file)), cdrom, ro))
    }

    /// Set the configuration for a LUN. This can only be done if a LUN is newly
    /// created and does not have an associated file set yet.
    pub fn set_lun(&self, lun: u8, fd: BorrowedFd, cdrom: bool, ro: bool) -> Result<()> {
        let name = format!("lun.{lun}");
        let path = Path::new(&name);

        // The file path must be written last.
        write_configfs_file(
            &self.path,
            &self.dir,
            &path.join("cdrom"),
            &[IoSlice::new(if cdrom { b"1\n" } else { b"0\n" })],
        )?;
        write_configfs_file(
            &self.path,
            &self.dir,
            &path.join("ro"),
            &[IoSlice::new(if ro { b"1\n" } else { b"0\n" })],
        )?;
        write_configfs_file(
            &self.path,
            &self.dir,
            &path.join("file"),
            &[IoSlice::new(
                format!(
                    "/proc/{}/fd/{}\n",
                    rustix::process::getpid().as_raw_nonzero(),
                    fd.as_raw_fd()
                )
                .as_bytes(),
            )],
        )?;

        Ok(())
    }
}
