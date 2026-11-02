// SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

use std::{fs::File, os::unix::net::UnixStream, path::PathBuf};

use anyhow::{Context, Result, bail};
use byteorder::{ReadBytesExt, WriteBytesExt};
use winnow_args::{Args, Subcommand, ValueEnum};

use crate::{
    daemon,
    message::{
        self, FromSocket, GetFunctionsRequest, GetMassStorageRequest, MassStorageDevice, Request,
        Response, SetMassStorageRequest, ToSocket,
    },
};

fn negotiate_protocol(stream: &mut UnixStream) -> Result<()> {
    stream
        .write_u8(message::PROTOCOL_VERSION)
        .context("Failed to send protocol version")?;

    let ack = stream
        .read_u8()
        .context("Failed to receive protocol version acknowledgement")?;
    match ack {
        1 => {}
        0 => bail!(
            "Daemon does not support protocol version: {}",
            message::PROTOCOL_VERSION
        ),
        n => bail!("Invalid protocol version acknowledgement: {n}"),
    }

    Ok(())
}

pub fn subcommand_client(cli: &ClientCli) -> Result<()> {
    let mut stream = UnixStream::connect_addr(&daemon::socket_addr())
        .context("Failed to connect to domain socket")?;

    negotiate_protocol(&mut stream)?;

    match &cli.command {
        ClientCommand::GetFunctions(_) => {
            let request = Request::GetFunctions(GetFunctionsRequest);
            request
                .to_socket(&mut stream)
                .with_context(|| format!("Failed to send request: {request:?}"))?;

            let response =
                Response::from_socket(&mut stream).context("Failed to receive response")?;

            match response {
                Response::Error(r) => bail!("{}", r.message),
                Response::GetFunctions(r) => {
                    for (config, function) in r.functions {
                        println!("{config:?} -> {function:?}");
                    }
                }
                r => bail!("Invalid response: {r:?}"),
            }
        }
        ClientCommand::SetMassStorage(c) => {
            if c.file.len() != c.type_.len() {
                bail!(
                    "Number of disk images ({}) and device types ({}) must match",
                    c.file.len(),
                    c.type_.len(),
                );
            }

            let mut devices = vec![];

            for (type_, path) in c.type_.iter().zip(c.file.iter()) {
                let file =
                    File::open(path).with_context(|| format!("Failed to open file: {path:?}"))?;

                devices.push(MassStorageDevice {
                    fd: file.into(),
                    cdrom: *type_ == MassStorageType::Cdrom,
                    ro: *type_ != MassStorageType::DiskRw,
                });
            }

            let request = Request::SetMassStorage(SetMassStorageRequest { devices });
            request
                .to_socket(&mut stream)
                .with_context(|| format!("Failed to send request: {request:?}"))?;

            let response =
                Response::from_socket(&mut stream).context("Failed to receive response")?;

            match response {
                Response::Error(r) => bail!("{}", r.message),
                Response::SetMassStorage(_) => {}
                r => bail!("Invalid response: {r:?}"),
            }
        }
        ClientCommand::GetMassStorage(_) => {
            let request = Request::GetMassStorage(GetMassStorageRequest);
            request
                .to_socket(&mut stream)
                .with_context(|| format!("Failed to send request: {request:?}"))?;

            let response =
                Response::from_socket(&mut stream).context("Failed to receive response")?;

            match response {
                Response::Error(r) => bail!("{}", r.message),
                Response::GetMassStorage(r) => {
                    for device in r.devices {
                        let type_ = match (device.cdrom, device.ro) {
                            (true, _) => "cdrom",
                            (false, true) => "disk-ro",
                            (false, false) => "disk-rw",
                        };

                        println!("{type_} -> {:?}", device.file);
                    }
                }
                r => bail!("Invalid response: {r:?}"),
            }
        }
    }

    Ok(())
}

/// Get currently active USB controller functions.
#[derive(Debug, Args)]
struct GetFunctionsCli;

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
enum MassStorageType {
    Cdrom,
    DiskRo,
    DiskRw,
}

/// Set USB controller to emulate mass storage devices.
///
/// The controller can emulate multiple mass storage devices at the same time.
/// To do so, pass in -f/--file and -t/--type multiple times.
///
/// This command always replaces all mass storage devices. To remove all of
/// them, don't specify any files.
#[derive(Debug, Args)]
struct SetMassStorageCli {
    /// Disk image or ISO file.
    #[arg(short, long)]
    file: Vec<PathBuf>,

    /// Mass storage device type.
    #[arg(short, long = "type")]
    type_: Vec<MassStorageType>,
}

/// Get currently active mass storage devices.
#[derive(Debug, Args)]
struct GetMassStorageCli;

#[allow(clippy::enum_variant_names)]
#[derive(Debug, Subcommand)]
enum ClientCommand {
    GetFunctions(GetFunctionsCli),
    SetMassStorage(SetMassStorageCli),
    GetMassStorage(GetMassStorageCli),
}

/// Send messages to daemon.
#[derive(Debug, Args)]
pub struct ClientCli {
    #[arg(subcommand)]
    command: ClientCommand,
}
