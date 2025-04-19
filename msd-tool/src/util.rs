// SPDX-FileCopyrightText: 2024 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

use std::{
    ffi::OsString,
    io,
    os::fd::{AsFd, OwnedFd},
    path::PathBuf,
};

use cap_std::{
    ambient_authority,
    fs::{Dir, ReadDir},
};
use rustix::{
    io::Errno,
    process::{Pid, Signal},
};
use tracing::debug;

pub const CONFIGFS_MAGIC: u32 = 0x62656570;
pub const PROC_SUPER_MAGIC: u32 = 0x9fa0;
pub const SELINUX_MAGIC: u32 = 0xf97cff8c;

/// Ensure that the fd refers to a file that lives on the specified type of
/// filesystem. This prevents reading a "fake" file backed by FUSE or similar.
pub fn check_fs_magic<T: AsFd>(fd: T, magic: u32) -> io::Result<T> {
    let statfs = rustix::fs::fstatfs(&fd)?;

    // f_type has different types on different architectures.
    if statfs.f_type as u32 != magic {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Cannot trust due to bad filesystem magic",
        ));
    }

    Ok(fd)
}

// The NDK has the pidfd constants, but the libc crate doesn't yet, so rustix
// doesn't enable the functionality for Android.

#[cfg(target_os = "linux")]
#[inline]
pub fn pidfd_open(pid: Pid) -> Result<OwnedFd, Errno> {
    use rustix::process::PidfdFlags;

    rustix::process::pidfd_open(pid, PidfdFlags::empty())
}

#[cfg(target_os = "android")]
#[inline]
pub fn pidfd_open(pid: Pid) -> Result<OwnedFd, Errno> {
    use std::os::fd::FromRawFd;

    unsafe {
        // Same syscall number on every architecture.
        let ret = libc::syscall(434, pid, 0);
        if ret == -1 {
            return Err(Errno::from_raw_os_error(*libc::__errno()));
        }

        Ok(OwnedFd::from_raw_fd(ret as i32))
    }
}

#[cfg(target_os = "linux")]
#[inline]
pub fn pidfd_send_signal<Fd: AsFd>(pidfd: Fd, sig: Signal) -> Result<(), Errno> {
    rustix::process::pidfd_send_signal(pidfd, sig)
}

#[cfg(target_os = "android")]
#[inline]
pub fn pidfd_send_signal<Fd: AsFd>(pidfd: Fd, sig: Signal) -> Result<(), Errno> {
    unsafe {
        // Same syscall number on every architecture.
        let ret = libc::syscall(424, pidfd.as_fd(), sig, 0usize, 0u32);
        if ret == -1 {
            return Err(Errno::from_raw_os_error(*libc::__errno()));
        }

        Ok(())
    }
}

/// Iterate through all PIDs, yielding a pidfd and the process executable name.
/// Kernel threads, PIDs that disappear during procfs traversal, and PIDs that
/// cannot be read due to permissions are ignored.
pub struct ProcessIter {
    dir: Dir,
    entries: ReadDir,
}

impl ProcessIter {
    pub fn new() -> io::Result<Self> {
        let dir = Dir::open_ambient_dir("/proc", ambient_authority())
            .and_then(|d| check_fs_magic(d, PROC_SUPER_MAGIC))?;
        let entries = dir.entries()?;

        Ok(Self { dir, entries })
    }
}

impl Iterator for ProcessIter {
    type Item = io::Result<(OwnedFd, OsString)>;

    fn next(&mut self) -> Option<Self::Item> {
        for entry in &mut self.entries {
            let entry = match entry {
                Ok(e) => e,
                Err(e) => return Some(Err(e)),
            };

            let Some(pid) = entry
                .file_name()
                .to_str()
                .and_then(|s| s.parse::<i32>().ok())
                .and_then(Pid::from_raw)
            else {
                continue;
            };

            let pidfd = match pidfd_open(pid) {
                Ok(c) => c,
                Err(e)
                    if e.kind() == io::ErrorKind::NotFound
                        || e.kind() == io::ErrorKind::PermissionDenied =>
                {
                    continue
                }
                Err(e) => return Some(Err(e.into())),
            };

            let mut path = PathBuf::from(entry.file_name());
            path.push("exe");

            // ENOENT in this case is not due to disappearing PIDs, but rather
            // PIDs being kernel threads, which don't have a corresponding
            // executable.
            let target = match self.dir.read_link_contents(&path) {
                Ok(c) => c,
                Err(e)
                    if e.kind() == io::ErrorKind::NotFound
                        || e.kind() == io::ErrorKind::PermissionDenied =>
                {
                    continue
                }
                Err(e) => return Some(Err(e)),
            };

            let Some(file_name) = target.file_name() else {
                continue;
            };

            return Some(Ok((pidfd, file_name.to_owned())));
        }

        None
    }
}

/// Send SIGSTOP to a process when constructed and SIGCONT when dropped.
pub struct ProcessStopper(OwnedFd);

impl ProcessStopper {
    pub fn new(pidfd: OwnedFd) -> Result<Self, Errno> {
        let result = Self(pidfd);
        result.stop()?;
        Ok(result)
    }

    pub fn stop(&self) -> Result<(), Errno> {
        debug!("Sending SIGSTOP to {:?}", self.0);
        pidfd_send_signal(&self.0, Signal::STOP)
    }

    pub fn cont(&self) -> Result<(), Errno> {
        debug!("Sending SIGCONT to {:?}", self.0);
        pidfd_send_signal(&self.0, Signal::CONT)
    }
}

impl Drop for ProcessStopper {
    fn drop(&mut self) {
        let _ = self.cont();
    }
}
