// SPDX-FileCopyrightText: 2024 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

//! This module implements the daemon that runs as the system user and listens
//! for requests from the app. The only actions possible are querying the
//! currently active functions and setting the USB controller to emulate mass
//! storage devices.
//!
//! Access control is handled entirely by the SELinux policy. If SELinux is not
//! enforcing at the time of the connection, the connection will be terminated.
//!
//! Protocol violations terminate the connection. Only valid, but failed,
//! requests result in an [`ErrorResponse`].

use std::{
    collections::BTreeMap,
    ffi::{OsStr, OsString},
    fs::File,
    io,
    os::{
        fd::AsFd,
        unix::net::{SocketAddr, UnixListener, UnixStream},
    },
    path::Path,
    sync::Mutex,
    thread,
};

#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(target_os = "linux")]
use std::os::linux::net::SocketAddrExt;

use anyhow::{anyhow, bail, Context, Result};
use byteorder::{ReadBytesExt, WriteBytesExt};
use clap::Parser;
use rustix::{
    fs::{FileType, Gid, Uid},
    thread::{CapabilityFlags, CapabilitySets},
};
use tracing::{debug, error, info, info_span, warn};

use crate::{
    message::{
        self, ErrorResponse, FromSocket, GetFunctionsResponse, Request, Response,
        SetMassStorageRequest, SetMassStorageResponse, ToSocket,
    },
    usb::UsbGadget,
    util::{self, ProcessStopper},
};

const SELINUX_ENFORCE: &str = "/sys/fs/selinux/enforce";

// AOSP hardcodes these.
const GADGET_ROOT: &str = "/config/usb_gadget/g1";
const CONFIGS_NAME: &str = "b.1";

const FUNCTION_NAME: &str = "mass_storage.msd";
const CONFIG_NAME: &str = "msd";

const GADGET_HAL_PROCESS: &str = "android.hardware.usb.gadget-service";

pub fn socket_addr() -> SocketAddr {
    SocketAddr::from_abstract_name("msdd").expect("Invalid abstract socket name")
}

/// Check that SELinux is enabled, enforcing, and that the policy seems to be
/// correct. This acts as a sanity check since we rely on SELinux for access
/// control.
fn check_selinux() -> Result<()> {
    let path = Path::new(SELINUX_ENFORCE);

    let mut file = File::open(path)
        .and_then(|f| util::check_fs_magic(f, util::SELINUX_MAGIC))
        .with_context(|| format!("Failed to open file: {path:?}"))?;

    let value = file
        .read_u8()
        .with_context(|| format!("Failed to read file: {path:?}"))?;

    if value != b'1' {
        bail!("Denying connection because SELinux is not enforcing");
    }

    // Our policy denies connections to ourselves. Try it to test that the
    // policy is actually loaded.
    match UnixStream::connect_addr(&socket_addr()) {
        Ok(_) => bail!("Denying connection because SELinux policy is broken"),
        Err(e) if e.kind() == io::ErrorKind::PermissionDenied => {}
        Err(e) => return Err(e).context("Self connection failed for unexpected reason"),
    }

    Ok(())
}

#[cfg(target_os = "android")]
fn usb_controller() -> Result<Option<String>> {
    const PROPERTY: &str = "sys.usb.controller";

    system_properties::read(PROPERTY)
        .with_context(|| format!("Failed to query property: {PROPERTY}"))
}

#[cfg(target_os = "linux")]
fn usb_controller() -> Result<Option<String>> {
    Ok(None)
}

fn negotiate_protocol(stream: &mut UnixStream) -> Result<()> {
    let client_version = stream
        .read_u8()
        .context("Failed to receive protocol version")?;
    if client_version != message::PROTOCOL_VERSION {
        stream
            .write_u8(0)
            .context("Failed to send protocol version rejection")?;

        bail!("Unsupported client protocol version: {client_version}");
    }

    stream
        .write_u8(1)
        .context("Failed to send protocol version acknowledgement")?;

    Ok(())
}

fn handle_get_functions_request() -> Result<BTreeMap<OsString, OsString>> {
    let gadget = UsbGadget::new(GADGET_ROOT, CONFIGS_NAME)?;

    gadget.configs()
}

fn handle_set_mass_storage_request(request: &SetMassStorageRequest) -> Result<()> {
    static LOCK: Mutex<()> = Mutex::new(());
    let _lock = LOCK.lock().unwrap();

    for device in &request.devices {
        let stat = rustix::fs::fstat(&device.fd)
            .with_context(|| format!("Failed to stat file: {:?}", device.fd))?;
        let file_type = FileType::from_raw_mode(stat.st_mode);

        if file_type != FileType::RegularFile {
            bail!("Not a regular file: {:?}: {file_type:?}", device.fd);
        }
    }

    let function_name = OsStr::new(FUNCTION_NAME);
    let config_name = OsStr::new(CONFIG_NAME);
    let gadget = UsbGadget::new(GADGET_ROOT, CONFIGS_NAME)?;

    // We need to SIGSTOP this process while we make our changes to prevent it
    // from constantly trying to ensure that UDC is set to the expected value.
    // Stopping the `vendor.usb-gadget-hal` init service would be cleaner, but
    // does not work because the HAL fails restore its state properly after it
    // starts back up, causing UDC to be cleared every time the device is
    // unplugged.
    let gadget_hal_stoppers = util::find_process(OsStr::new(GADGET_HAL_PROCESS))
        .and_then(|pidfds| {
            pidfds
                .into_iter()
                .map(|fd| ProcessStopper::new(fd).map_err(io::Error::from))
                .collect::<io::Result<Vec<_>>>()
        })
        .context("Failed to search for gadget HAL process")?;
    if gadget_hal_stoppers.is_empty() {
        bail!("Failed to find gadget HAL process");
    }

    let Some(controller) = usb_controller()? else {
        bail!("Cannot determine ID of USB controller");
    };

    debug!("Disassociating gadget config from controller");
    gadget.set_controller(None)?;

    if gadget.delete_config(config_name)? {
        debug!("Deleted old mass storage config");
    }

    // Extra LUNs must be deleted first, but lun.0 cannot be deleted.
    if let Some(function) = gadget.open_mass_storage_function(function_name)? {
        for lun in function.luns()? {
            if lun != 0 && function.delete_lun(lun)? {
                debug!("Deleted LUN #{lun}")
            }
        }
    }
    if gadget.delete_function(function_name)? {
        debug!("Deleted old mass storage function");
    }

    if !request.devices.is_empty() {
        if gadget.create_function(function_name)? {
            debug!("Created mass storage function");
        }

        let function = gadget
            .open_mass_storage_function(function_name)?
            .ok_or_else(|| anyhow!("Newly created function does not exist: {function_name:?}"))?;
        for (lun, device) in request.devices.iter().enumerate() {
            // lun.0 exists by default.
            if lun > 0 && function.create_lun(lun as u8)? {
                debug!("Created LUN #{lun}");
            }

            debug!("Associating LUN #{lun} with {device:?}");
            function.set_lun(lun as u8, device.fd.as_fd(), device.cdrom, device.ro)?;
        }

        if gadget.create_config(config_name, function_name)? {
            debug!("Created mass storage config");
        }
    }

    debug!("Applying config to USB controller: {controller:?}");
    gadget.set_controller(Some(&controller))?;

    Ok(())
}

fn handle_request(request: &Request) -> Response {
    let ret = match request {
        Request::GetFunctions(_) => handle_get_functions_request()
            .map(|functions| Response::GetFunctions(GetFunctionsResponse { functions })),
        Request::SetMassStorage(r) => handle_set_mass_storage_request(r)
            .map(|_| Response::SetMassStorage(SetMassStorageResponse)),
    };

    ret.unwrap_or_else(|e| {
        warn!("{e:?}");

        Response::Error(ErrorResponse {
            message: format!("{e:?}"),
        })
    })
}

fn handle_client(mut stream: UnixStream) -> Result<()> {
    check_selinux()?;
    negotiate_protocol(&mut stream)?;

    loop {
        let request = match Request::from_socket(&mut stream) {
            Ok(r) => r,
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => break Ok(()),
            Err(e) => return Err(e).context("Failed to receive request"),
        };

        let response = handle_request(&request);

        response
            .to_socket(&mut stream)
            .with_context(|| format!("Failed to send response: {response:?}"))?;
    }
}

fn drop_privileges() -> Result<()> {
    // The only thing we need root level permissions for is chown'ing newly
    // created files on configfs. Unlike other filesystems, newly created files
    // on configfs are always owned by root:root. There was a patch from 2021 to
    // fix this behavior, but it was never accepted.
    // https://lore.kernel.org/lkml/20210123205516.2738060-1-zenczykowski@gmail.com/
    //
    // For ADB/MTP/etc., AOSP works around this by having an init script create
    // the paths on configfs and chown them appropriately. This approach does
    // not work for us because creating a LUN that's not associated with a file
    // still results in a 0-sized device being advertised. This prevents some
    // machines from booting from another mass storage device. Bootable devices
    // is an important use case for MSD, so we're stuck with requiring elevated
    // privileges.
    //
    // There are 2 ways the daemon can be run. If we're running runing as
    // system:system, then the parent process is responsible for execve'ing with
    // CAP_CHROOT allowed. If we're running as root:root, then we drop all
    // capabilities besides CAP_CHROOT and drop privileges to system:system.

    let system_uid = unsafe { Uid::from_raw(1000) };
    let system_gid = unsafe { Gid::from_raw(1000) };
    let real_uid = rustix::process::getuid();
    let real_gid = rustix::process::getgid();

    if real_uid == system_uid && real_gid == system_gid {
        let capability_set =
            rustix::thread::capabilities(None).context("Failed to query capabilities")?;

        if !capability_set.effective.contains(CapabilityFlags::CHOWN) {
            bail!("CAP_CHOWN is required when running as system user");
        }
    } else if real_uid == Uid::ROOT && real_gid == Gid::ROOT {
        rustix::thread::set_keep_capabilities(true)
            .context("Failed to set keep capabilities flag")?;

        rustix::thread::set_thread_groups(&[]).context("Failed to drop supplementary groups")?;
        rustix::thread::set_thread_res_gid(system_gid, system_gid, system_gid)
            .context("Failed to switch GID to system group")?;
        rustix::thread::set_thread_res_uid(system_uid, system_uid, system_uid)
            .context("Failed to switch UID to system user")?;
    } else {
        bail!("Must run as root or system user, not {real_uid:?} {real_gid:?}");
    }

    let capability_set = CapabilitySets {
        effective: CapabilityFlags::CHOWN,
        permitted: CapabilityFlags::CHOWN,
        inheritable: CapabilityFlags::empty(),
    };

    rustix::thread::set_capabilities(None, capability_set)
        .context("Failed to drop capabilities")?;

    Ok(())
}

pub fn subcommand_daemon(_cli: &DaemonCli) -> Result<()> {
    drop_privileges()?;

    let listener =
        UnixListener::bind_addr(&socket_addr()).context("Failed to listen on domain socket")?;

    thread::scope(|scope| -> Result<()> {
        for stream in listener.incoming() {
            let stream = stream.context("Failed to accept incoming connection")?;
            let ucred = rustix::net::sockopt::get_socket_peercred(&stream)
                .context("Failed to get socket peer credentials")?;

            scope.spawn(move || {
                let _span = info_span!(
                    "peer",
                    pid = ucred.pid.as_raw_nonzero(),
                    uid = ucred.uid.as_raw(),
                    gid = ucred.gid.as_raw(),
                )
                .entered();

                if ucred.pid == rustix::process::getpid() {
                    error!("SELinux rules are broken; able to connect to self");
                    return;
                }

                info!("Received connection");

                if let Err(e) = handle_client(stream) {
                    error!("Thread failed: {e}");
                }
            });
        }

        unreachable!()
    })?;

    Ok(())
}

/// Run daemon.
#[derive(Debug, Parser)]
pub struct DaemonCli;
