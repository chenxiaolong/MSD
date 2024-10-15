// SPDX-FileCopyrightText: 2024 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

mod client;
mod daemon;
mod message;
mod sepatch;
mod usb;
mod util;

use std::{
    fmt,
    io::{self, IsTerminal},
    process::ExitCode,
};

use clap::{Parser, Subcommand, ValueEnum};
use tracing::{error, Level};

#[allow(clippy::enum_variant_names)]
#[derive(Debug, Subcommand)]
enum Command {
    Client(client::ClientCli),
    Daemon(daemon::DaemonCli),
    Sepatch(sepatch::SepatchCli),
}

#[derive(Debug, Clone, Copy, ValueEnum)]
pub enum LogTarget {
    Stderr,
    #[cfg(target_os = "android")]
    Logcat,
}

impl Default for LogTarget {
    fn default() -> Self {
        Self::Stderr
    }
}

impl fmt::Display for LogTarget {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.to_possible_value().ok_or(fmt::Error)?.get_name())
    }
}

const HEADING: &str = "Global options";

#[derive(Debug, Parser)]
struct Cli {
    #[command(subcommand)]
    command: Command,

    /// Where to output log messages.
    #[arg(long, global = true, value_name = "TARGET", default_value_t, help_heading = HEADING)]
    pub log_target: LogTarget,

    /// Lowest log message severity to output.
    #[arg(long, global = true, value_name = "LEVEL", default_value_t = Level::INFO, help_heading = HEADING)]
    pub log_level: Level,
}

fn init_logging(target: LogTarget, level: Level) {
    match target {
        LogTarget::Stderr => {
            tracing_subscriber::fmt()
                .with_writer(io::stderr)
                .with_ansi(io::stderr().is_terminal())
                .with_max_level(level)
                .init();
        }
        #[cfg(target_os = "android")]
        LogTarget::Logcat => {
            use tracing_logcat::{LogcatMakeWriter, LogcatTag};
            use tracing_subscriber::fmt::format::Format;

            let tag = LogcatTag::Fixed(env!("CARGO_PKG_NAME").to_owned());
            let writer = LogcatMakeWriter::new(tag).expect("Failed to initialize logcat writer");

            tracing_subscriber::fmt()
                .event_format(Format::default().with_level(false).without_time())
                .with_writer(writer)
                .with_ansi(false)
                .with_max_level(level)
                .init();
        }
    }
}

fn main() -> ExitCode {
    let cli = Cli::parse();

    init_logging(cli.log_target, cli.log_level);

    let ret = match cli.command {
        Command::Client(c) => client::subcommand_client(&c),
        Command::Daemon(c) => daemon::subcommand_daemon(&c),
        Command::Sepatch(c) => sepatch::subcommand_sepatch(&c),
    };

    match ret {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            error!("{e:?}");
            ExitCode::FAILURE
        }
    }
}
