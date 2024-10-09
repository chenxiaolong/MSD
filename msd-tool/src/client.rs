// SPDX-FileCopyrightText: 2024 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

use std::{fs::File, os::unix::net::UnixStream, path::PathBuf};

use anyhow::{bail, Context, Result};
use byteorder::{ReadBytesExt, WriteBytesExt};
use clap::{CommandFactory, Parser, Subcommand, ValueEnum};

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
                let (arg_id, actual_len, expected_len) = if c.file.len() < c.type_.len() {
                    ("file", c.file.len(), c.type_.len())
                } else {
                    ("type_", c.type_.len(), c.file.len())
                };

                let mut command = SetMassStorageCli::command();
                command.build();

                let arg = command
                    .get_arguments()
                    .find(|a| a.get_id() == arg_id)
                    .expect("argument not found");

                let mut error = clap::Error::new(clap::error::ErrorKind::WrongNumberOfValues)
                    .with_cmd(&command);
                error.insert(
                    clap::error::ContextKind::InvalidArg,
                    clap::error::ContextValue::String(arg.to_string()),
                );
                error.insert(
                    clap::error::ContextKind::ActualNumValues,
                    clap::error::ContextValue::Number(actual_len as isize),
                );
                error.insert(
                    clap::error::ContextKind::ExpectedNumValues,
                    clap::error::ContextValue::Number(expected_len as isize),
                );
                error.exit();
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
                            (true, _) => MassStorageType::Cdrom,
                            (false, true) => MassStorageType::DiskRo,
                            (false, false) => MassStorageType::DiskRw,
                        };
                        let type_value = type_.to_possible_value().unwrap();

                        println!("{} -> {:?}", type_value.get_name(), device.file);
                    }
                }
                r => bail!("Invalid response: {r:?}"),
            }
        }
    }

    Ok(())
}

/// Get currently active USB controller functions.
#[derive(Debug, Parser)]
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
#[derive(Debug, Parser)]
struct SetMassStorageCli {
    /// Disk image or ISO file.
    #[clap(short, long, value_parser)]
    file: Vec<PathBuf>,

    /// Mass storage device type.
    #[clap(short, long)]
    type_: Vec<MassStorageType>,
}

/// Get currently active mass storage devices.
#[derive(Debug, Parser)]
struct GetMassStorageCli;

#[allow(clippy::enum_variant_names)]
#[derive(Debug, Subcommand)]
enum ClientCommand {
    GetFunctions(GetFunctionsCli),
    SetMassStorage(SetMassStorageCli),
    GetMassStorage(GetMassStorageCli),
}

/// Send messages to daemon.
#[derive(Debug, Parser)]
pub struct ClientCli {
    #[command(subcommand)]
    command: ClientCommand,
}
