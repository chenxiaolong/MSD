// SPDX-FileCopyrightText: 2024 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

use std::{
    collections::BTreeMap,
    ffi::OsString,
    io::{self, IoSlice, IoSliceMut, Read, Write},
    os::{
        fd::{AsFd, BorrowedFd, OwnedFd},
        unix::{
            ffi::{OsStrExt, OsStringExt},
            net::UnixStream,
        },
    },
    path::PathBuf,
};

use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use rustix::{
    io::Errno,
    net::{
        RecvAncillaryBuffer, RecvAncillaryMessage, RecvFlags, SendAncillaryBuffer,
        SendAncillaryMessage, SendFlags,
    },
};

pub const PROTOCOL_VERSION: u8 = 1;

/// Send a list of fds to a unix socket via ancillary data attached to a single
/// byte message.
fn send_fds(stream: &mut UnixStream, fds: &[BorrowedFd]) -> Result<(), Errno> {
    if fds.is_empty() {
        return Ok(());
    }

    let mut space = vec![0; rustix::cmsg_space!(ScmRights(fds.len()))];
    let mut cmsg_buf = SendAncillaryBuffer::new(&mut space);

    if !cmsg_buf.push(SendAncillaryMessage::ScmRights(fds)) {
        panic!("Failed to push fd into cmsg buffer");
    }

    rustix::net::sendmsg(
        stream,
        &[IoSlice::new(&[0])],
        &mut cmsg_buf,
        SendFlags::empty(),
    )?;

    Ok(())
}

/// Receive a list of fds from a unix socket via ancillary data attached to a
/// single byte message. The number of fds to receive must be known in advance
/// in order to allocate the proper buffer size.
fn receive_fds(stream: &mut UnixStream, num_fds: usize) -> io::Result<Vec<OwnedFd>> {
    if num_fds == 0 {
        return Ok(vec![]);
    }

    let mut space = vec![0; rustix::cmsg_space!(ScmRights(num_fds))];
    let mut cmsg_buf = RecvAncillaryBuffer::new(&mut space);
    let ret = rustix::net::recvmsg(
        stream,
        &mut [IoSliceMut::new(&mut [0])],
        &mut cmsg_buf,
        RecvFlags::WAITALL,
    )?;
    if ret.bytes == 0 {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "Received no data from socket",
        ));
    }

    let mut iter = cmsg_buf.drain();

    let Some(msg) = iter.next() else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Ancillary data has no message",
        ));
    };

    if iter.next().is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Ancillary data has more than one message",
        ));
    }

    let RecvAncillaryMessage::ScmRights(fds) = msg else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Ancillary data message does not contain fds",
        ));
    };

    if fds.len() != num_fds {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("Expected {num_fds} fds, but received {}", fds.len()),
        ));
    }

    Ok(fds.collect())
}

/// Read a length-prefixed data from the socket.
fn read_data(stream: &mut UnixStream) -> io::Result<Vec<u8>> {
    let size = stream.read_u16::<LittleEndian>()?;
    let mut buf = vec![0u8; size.into()];

    stream.read_exact(&mut buf)?;

    Ok(buf)
}

/// Write a length-prefixed data to the socket.
fn write_data(stream: &mut UnixStream, buf: &[u8]) -> io::Result<()> {
    if buf.len() > u16::MAX.into() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Data length exceeds u16 bounds",
        ));
    }

    stream.write_u16::<LittleEndian>(buf.len() as u16)?;
    stream.write_all(buf)?;

    Ok(())
}

pub trait MessageId {
    const ID: u8;

    fn id(&self) -> u8 {
        Self::ID
    }
}

pub trait FromSocket: Sized {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self>;
}

pub trait ToSocket {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()>;
}

#[derive(Debug, Clone)]
pub struct ErrorResponse {
    pub message: String,
}

impl MessageId for ErrorResponse {
    const ID: u8 = 1;
}

impl FromSocket for ErrorResponse {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let data = read_data(stream)?;
        let message =
            String::from_utf8(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;

        Ok(Self { message })
    }
}

impl ToSocket for ErrorResponse {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        write_data(stream, self.message.as_bytes())
    }
}

#[derive(Debug, Clone, Copy)]
pub struct GetFunctionsRequest;

impl MessageId for GetFunctionsRequest {
    const ID: u8 = 2;
}

impl FromSocket for GetFunctionsRequest {
    fn from_socket(_stream: &mut UnixStream) -> io::Result<Self> {
        Ok(Self)
    }
}

impl ToSocket for GetFunctionsRequest {
    fn to_socket(&self, _stream: &mut UnixStream) -> io::Result<()> {
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct GetFunctionsResponse {
    pub functions: BTreeMap<OsString, OsString>,
}

impl MessageId for GetFunctionsResponse {
    const ID: u8 = 3;
}

impl FromSocket for GetFunctionsResponse {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let num_functions = stream.read_u8()?;
        let mut functions = BTreeMap::new();

        for _ in 0..num_functions {
            let config = read_data(stream)?;
            let function = read_data(stream)?;

            functions.insert(OsString::from_vec(config), OsString::from_vec(function));
        }

        Ok(Self { functions })
    }
}

impl ToSocket for GetFunctionsResponse {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        if self.functions.len() > u8::MAX.into() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Number of functions exceeds u8 bounds",
            ));
        }

        stream.write_u8(self.functions.len() as u8)?;
        for (config, function) in &self.functions {
            write_data(stream, config.as_bytes())?;
            write_data(stream, function.as_bytes())?;
        }

        Ok(())
    }
}

#[derive(Debug)]
pub struct MassStorageDevice {
    pub fd: OwnedFd,
    pub cdrom: bool,
    pub ro: bool,
}

impl FromSocket for MassStorageDevice {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let fd = receive_fds(stream, 1)?.pop().unwrap();
        let cdrom = stream.read_u8()? != 0;
        let ro = stream.read_u8()? != 0;

        Ok(Self { fd, cdrom, ro })
    }
}

impl ToSocket for MassStorageDevice {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        send_fds(stream, &[self.fd.as_fd()])?;
        stream.write_u8(self.cdrom.into())?;
        stream.write_u8(self.ro.into())?;

        Ok(())
    }
}

#[derive(Debug)]
pub struct SetMassStorageRequest {
    pub devices: Vec<MassStorageDevice>,
}

impl MessageId for SetMassStorageRequest {
    const ID: u8 = 4;
}

impl FromSocket for SetMassStorageRequest {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let num_devices = stream.read_u8()?;
        let mut devices = vec![];

        for _ in 0..num_devices {
            let device = MassStorageDevice::from_socket(stream)?;
            devices.push(device);
        }

        Ok(Self { devices })
    }
}

impl ToSocket for SetMassStorageRequest {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        if self.devices.len() > u8::MAX.into() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Number of devices exceeds u8 bounds",
            ));
        }

        stream.write_u8(self.devices.len() as u8)?;
        for device in &self.devices {
            device.to_socket(stream)?;
        }

        Ok(())
    }
}

#[derive(Debug, Clone, Copy)]
pub struct SetMassStorageResponse;

impl MessageId for SetMassStorageResponse {
    const ID: u8 = 5;
}

impl FromSocket for SetMassStorageResponse {
    fn from_socket(_stream: &mut UnixStream) -> io::Result<Self> {
        Ok(Self)
    }
}

impl ToSocket for SetMassStorageResponse {
    fn to_socket(&self, _stream: &mut UnixStream) -> io::Result<()> {
        Ok(())
    }
}

#[derive(Debug)]
pub struct ActiveMassStorageDevice {
    pub file: PathBuf,
    pub cdrom: bool,
    pub ro: bool,
}

impl FromSocket for ActiveMassStorageDevice {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let file = read_data(stream)
            .map(OsString::from_vec)
            .map(PathBuf::from)?;
        let cdrom = stream.read_u8()? != 0;
        let ro = stream.read_u8()? != 0;

        Ok(Self { file, cdrom, ro })
    }
}

impl ToSocket for ActiveMassStorageDevice {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        write_data(stream, self.file.as_os_str().as_bytes())?;
        stream.write_u8(self.cdrom.into())?;
        stream.write_u8(self.ro.into())?;

        Ok(())
    }
}

#[derive(Debug, Clone, Copy)]
pub struct GetMassStorageRequest;

impl MessageId for GetMassStorageRequest {
    const ID: u8 = 6;
}

impl FromSocket for GetMassStorageRequest {
    fn from_socket(_stream: &mut UnixStream) -> io::Result<Self> {
        Ok(Self)
    }
}

impl ToSocket for GetMassStorageRequest {
    fn to_socket(&self, _stream: &mut UnixStream) -> io::Result<()> {
        Ok(())
    }
}

#[derive(Debug)]
pub struct GetMassStorageResponse {
    pub devices: Vec<ActiveMassStorageDevice>,
}

impl MessageId for GetMassStorageResponse {
    const ID: u8 = 7;
}

impl FromSocket for GetMassStorageResponse {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let num_devices = stream.read_u8()?;
        let mut devices = vec![];

        for _ in 0..num_devices {
            let device = ActiveMassStorageDevice::from_socket(stream)?;
            devices.push(device);
        }

        Ok(Self { devices })
    }
}

impl ToSocket for GetMassStorageResponse {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        if self.devices.len() > u8::MAX.into() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Number of devices exceeds u8 bounds",
            ));
        }

        stream.write_u8(self.devices.len() as u8)?;
        for device in &self.devices {
            device.to_socket(stream)?;
        }

        Ok(())
    }
}

#[derive(Debug)]
pub enum Request {
    GetFunctions(GetFunctionsRequest),
    SetMassStorage(SetMassStorageRequest),
    GetMassStorage(GetMassStorageRequest),
}

impl FromSocket for Request {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let id = stream.read_u8()?;

        match id {
            GetFunctionsRequest::ID => {
                GetFunctionsRequest::from_socket(stream).map(Self::GetFunctions)
            }
            SetMassStorageRequest::ID => {
                SetMassStorageRequest::from_socket(stream).map(Self::SetMassStorage)
            }
            GetMassStorageRequest::ID => {
                GetMassStorageRequest::from_socket(stream).map(Self::GetMassStorage)
            }
            _ => Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Invalid message ID: {id}"),
            )),
        }
    }
}

impl ToSocket for Request {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        let id = match self {
            Self::GetFunctions(m) => m.id(),
            Self::SetMassStorage(m) => m.id(),
            Self::GetMassStorage(m) => m.id(),
        };

        stream.write_u8(id)?;

        match self {
            Self::GetFunctions(m) => m.to_socket(stream),
            Self::SetMassStorage(m) => m.to_socket(stream),
            Self::GetMassStorage(m) => m.to_socket(stream),
        }
    }
}

#[derive(Debug)]
pub enum Response {
    Error(ErrorResponse),
    GetFunctions(GetFunctionsResponse),
    SetMassStorage(SetMassStorageResponse),
    GetMassStorage(GetMassStorageResponse),
}

impl FromSocket for Response {
    fn from_socket(stream: &mut UnixStream) -> io::Result<Self> {
        let id = stream.read_u8()?;

        match id {
            ErrorResponse::ID => ErrorResponse::from_socket(stream).map(Self::Error),
            GetFunctionsResponse::ID => {
                GetFunctionsResponse::from_socket(stream).map(Self::GetFunctions)
            }
            SetMassStorageResponse::ID => {
                SetMassStorageResponse::from_socket(stream).map(Self::SetMassStorage)
            }
            GetMassStorageResponse::ID => {
                GetMassStorageResponse::from_socket(stream).map(Self::GetMassStorage)
            }
            _ => Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Invalid message ID: {id}"),
            )),
        }
    }
}

impl ToSocket for Response {
    fn to_socket(&self, stream: &mut UnixStream) -> io::Result<()> {
        let id = match self {
            Self::Error(m) => m.id(),
            Self::GetFunctions(m) => m.id(),
            Self::SetMassStorage(m) => m.id(),
            Self::GetMassStorage(m) => m.id(),
        };

        stream.write_u8(id)?;

        match self {
            Self::Error(m) => m.to_socket(stream),
            Self::GetFunctions(m) => m.to_socket(stream),
            Self::SetMassStorage(m) => m.to_socket(stream),
            Self::GetMassStorage(m) => m.to_socket(stream),
        }
    }
}
