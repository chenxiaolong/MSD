// SPDX-License-Identifier: GPL-3.0-only

//! Authentication over private pipes inherited from the trusted app_process
//! supervisor. No identity or authorization response is read from the client.

use std::{
    fs::File,
    io::{Read, Write},
    sync::Mutex,
};

use anyhow::{Context, Result, bail};
use rustix::{
    event::{PollFd, PollFlags, Timespec, poll},
    fs::Uid,
};

struct Channel {
    input: File,
    output: File,
    failed: bool,
}

pub struct Authenticator(Mutex<Channel>);

impl Authenticator {
    pub fn new() -> Result<Self> {
        Ok(Self(Mutex::new(Channel {
            input: rustix::io::dup(std::io::stdin())?.into(),
            output: rustix::io::dup(std::io::stdout())?.into(),
            failed: false,
        })))
    }

    pub fn authorize(&self, uid: Uid) -> Result<()> {
        // Retain root's administrative CLI access. Neither shell nor the system
        // UID is implicitly authorized.
        if uid == Uid::ROOT {
            return Ok(());
        }
        let mut channel = self
            .0
            .lock()
            .map_err(|_| anyhow::anyhow!("Authenticator poisoned"))?;
        if channel.failed {
            bail!("Authentication supervisor unavailable");
        }
        let result = channel.query(uid.as_raw());
        // A timeout, EOF, or malformed response permanently poisons this channel:
        // a late response must never authorize a different client's request.
        if result.is_err() {
            channel.failed = true;
        }
        if !result? {
            bail!("Client UID {} is not the trusted MSD package", uid.as_raw());
        }
        Ok(())
    }
}

impl Channel {
    fn query(&mut self, uid: u32) -> Result<bool> {
        self.output
            .write_all(&uid.to_be_bytes())
            .context("Sending authentication query")?;
        self.output.flush()?;
        let mut fds = [PollFd::new(&self.input, PollFlags::IN)];
        if poll(
            &mut fds,
            Some(&Timespec {
                tv_sec: 5,
                tv_nsec: 0,
            }),
        )? == 0
        {
            bail!("Authentication supervisor timed out");
        }
        let mut response = [0];
        self.input
            .read_exact(&mut response)
            .context("Reading authentication result")?;
        match response[0] {
            0 => Ok(false),
            1 => Ok(true),
            value => bail!("Invalid authentication response: {value}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        os::{fd::OwnedFd, unix::net::UnixStream},
        thread,
    };

    fn channel() -> (Authenticator, UnixStream) {
        let (daemon, supervisor) = UnixStream::pair().unwrap();
        let input: OwnedFd = daemon.try_clone().unwrap().into();
        let output: OwnedFd = daemon.into();
        (
            Authenticator(Mutex::new(Channel {
                input: input.into(),
                output: output.into(),
                failed: false,
            })),
            supervisor,
        )
    }

    #[test]
    fn checks_full_uid_and_does_not_cache_authorization() {
        let (auth, mut supervisor) = channel();
        let worker = thread::spawn(move || {
            for (uid, answer) in [(10469u32, 1u8), (110469, 0), (10469, 0)] {
                let mut query = [0; 4];
                supervisor.read_exact(&mut query).unwrap();
                assert_eq!(u32::from_be_bytes(query), uid);
                supervisor.write_all(&[answer]).unwrap();
            }
        });
        assert!(auth.authorize(Uid::from_raw(10469)).is_ok());
        assert!(auth.authorize(Uid::from_raw(110469)).is_err());
        assert!(auth.authorize(Uid::from_raw(10469)).is_err());
        worker.join().unwrap();
    }

    #[test]
    fn malformed_response_permanently_fails_closed() {
        let (auth, mut supervisor) = channel();
        let worker = thread::spawn(move || {
            let mut query = [0; 4];
            supervisor.read_exact(&mut query).unwrap();
            supervisor.write_all(&[2, 1]).unwrap();
        });
        assert!(auth.authorize(Uid::from_raw(10469)).is_err());
        assert!(auth.authorize(Uid::from_raw(10469)).is_err());
        worker.join().unwrap();
    }

    #[test]
    fn supervisor_eof_fails_closed() {
        let (auth, mut supervisor) = channel();
        let worker = thread::spawn(move || {
            let mut query = [0; 4];
            supervisor.read_exact(&mut query).unwrap();
        });
        assert!(auth.authorize(Uid::from_raw(10469)).is_err());
        assert!(auth.authorize(Uid::from_raw(10469)).is_err());
        worker.join().unwrap();
    }

    #[test]
    fn only_root_bypasses_the_supervisor() {
        let (auth, mut supervisor) = channel();
        assert!(auth.authorize(Uid::ROOT).is_ok());
        let worker = thread::spawn(move || {
            for uid in [1000u32, 2000] {
                let mut query = [0; 4];
                supervisor.read_exact(&mut query).unwrap();
                assert_eq!(u32::from_be_bytes(query), uid);
                supervisor.write_all(&[0]).unwrap();
            }
        });
        assert!(auth.authorize(Uid::from_raw(1000)).is_err());
        assert!(auth.authorize(Uid::from_raw(2000)).is_err());
        worker.join().unwrap();
    }

    #[test]
    fn late_response_after_timeout_cannot_authorize_another_request() {
        let (auth, mut supervisor) = channel();
        let worker = thread::spawn(move || {
            let mut query = [0; 4];
            supervisor.read_exact(&mut query).unwrap();
            thread::sleep(std::time::Duration::from_millis(5200));
            supervisor.write_all(&[1]).unwrap();
        });
        assert!(auth.authorize(Uid::from_raw(10469)).is_err());
        worker.join().unwrap();
        assert!(auth.authorize(Uid::from_raw(10470)).is_err());
    }
}
