// SPDX-FileCopyrightText: 2024 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

use anyhow::{anyhow, bail, Context, Result};
use clap::{Args, Parser};
use sepatch::{PolicyDb, RuleAction};

fn read_policy(path: &Path) -> Result<PolicyDb> {
    let data = fs::read(path).with_context(|| format!("Failed to open for reading: {path:?}"))?;

    let mut warnings = vec![];
    let pdb = PolicyDb::from_raw(&data, &mut warnings).context("Failed to parse sepolicy")?;

    if !warnings.is_empty() {
        eprintln!("Warnings when loading sepolicy:");
        for warning in warnings {
            eprintln!("- {warning}");
        }
    }

    Ok(pdb)
}

fn write_policy(path: &Path, pdb: &PolicyDb) -> Result<()> {
    let mut warnings = vec![];
    let data = pdb
        .to_raw(&mut warnings)
        .context("Failed to build sepolicy")?;

    if !warnings.is_empty() {
        eprintln!("Warnings when saving sepolicy:");
        for warning in warnings {
            eprintln!("- {warning}");
        }
    }

    let mut file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(path)
        .with_context(|| format!("Failed to open for writing: {path:?}"))?;

    // Truncate only if needed. Some apps detect if the policy is modified
    // by looking at the modification timestamp of /sys/fs/selinux/load. A
    // write() syscall does not change mtime, but O_TRUNC does. Also, utimensat
    // does not work on selinuxfs.
    let metadata = file
        .metadata()
        .with_context(|| format!("Failed to stat file: {path:?}"))?;
    if metadata.len() > 0 {
        file.set_len(0)
            .with_context(|| format!("Failed to truncate file: {path:?}"))?;
    }

    let n = file
        .write(&data)
        .with_context(|| format!("Failed to write file: {path:?}"))?;
    if n != data.len() {
        bail!("Failed to write data in a single write call");
    }

    Ok(())
}

pub fn subcommand_sepatch(cli: &SepatchCli) -> Result<()> {
    let mut pdb = read_policy(cli.source.as_path())?;

    let n_source_type = "untrusted_app";
    let n_source_uffd_type = "untrusted_app_userfaultfd";
    let n_target_type = "msd_app";
    let n_target_uffd_type = "msd_app_userfaultfd";
    let n_daemon_type = "msd_daemon";

    macro_rules! r {
        ($name:expr) => {{
            let name = $name;
            pdb.get_role_id(name)
                .ok_or_else(|| anyhow!("Role not found: {name}"))
        }};
    }
    macro_rules! t {
        ($name:expr) => {{
            let name = $name;
            pdb.get_type_id(name)
                .ok_or_else(|| anyhow!("Type not found: {name}"))
        }};
    }
    macro_rules! c {
        ($name:expr) => {{
            let name = $name;
            pdb.get_class_id(name)
                .ok_or_else(|| anyhow!("Class not found: {name}"))
        }};
    }
    macro_rules! p {
        ($class_id:expr, $name:expr) => {{
            let class_id = $class_id;
            let name = $name;
            pdb.get_perm_id(class_id, name)
                .ok_or_else(|| anyhow!("Permission not found in {class_id:?}: {name}"))
        }};
    }

    let r_r = r!("r")?;

    let t_configfs = t!("configfs")?;
    let t_domain = t!("domain")?;
    let t_fuse = t!("fuse")?;
    let t_hal_usb_gadget_default = t!("hal_usb_gadget_default")?;
    let t_hal_usb_gadget_impl = match t!("hal_usb_gadget_impl") {
        Ok(t) => t,
        // Allow us to run an arbitrary process as a fake "HAL" in the emulator.
        Err(e) => t!("su").map_err(|_| e)?,
    };
    let t_init = t!("init")?;
    let t_kernel = t!("kernel")?;
    let t_mediaprovider = t!("mediaprovider")?;
    let t_mediaprovider_app = t!("mediaprovider_app")?;
    let t_mlstrustedsubject = t!("mlstrustedsubject")?;
    let t_selinuxfs = t!("selinuxfs")?;
    let t_shell = t!("shell")?;
    let t_system_file = t!("system_file")?;
    let t_usb_control_prop = t!("usb_control_prop")?;

    let c_capability = c!("capability")?;
    let p_capability_chown = p!(c_capability, "chown")?;
    let p_capability_setgid = p!(c_capability, "setgid")?;
    let p_capability_setuid = p!(c_capability, "setuid")?;

    let c_dir = c!("dir")?;
    let p_dir_add_name = p!(c_dir, "add_name")?;
    let p_dir_create = p!(c_dir, "create")?;
    let p_dir_open = p!(c_dir, "open")?;
    let p_dir_read = p!(c_dir, "read")?;
    let p_dir_remove_name = p!(c_dir, "remove_name")?;
    let p_dir_rmdir = p!(c_dir, "rmdir")?;
    let p_dir_search = p!(c_dir, "search")?;
    let p_dir_setattr = p!(c_dir, "setattr")?;
    let p_dir_write = p!(c_dir, "write")?;

    let c_fd = c!("fd")?;
    let p_fd_use = p!(c_fd, "use")?;

    let c_file = c!("file")?;
    let p_file_create = p!(c_file, "create")?;
    let p_file_entrypoint = p!(c_file, "entrypoint")?;
    let p_file_execute = p!(c_file, "execute")?;
    let p_file_getattr = p!(c_file, "getattr")?;
    let p_file_map = p!(c_file, "map")?;
    let p_file_open = p!(c_file, "open")?;
    let p_file_read = p!(c_file, "read")?;
    let p_file_setattr = p!(c_file, "setattr")?;
    let p_file_write = p!(c_file, "write")?;

    let c_lnk_file = c!("lnk_file")?;
    let p_lnk_file_create = p!(c_lnk_file, "create")?;
    let p_lnk_file_read = p!(c_lnk_file, "read")?;
    let p_lnk_file_unlink = p!(c_lnk_file, "unlink")?;

    let c_process = c!("process")?;
    let p_process_noatsecure = p!(c_process, "noatsecure")?;
    let p_process_rlimitinh = p!(c_process, "rlimitinh")?;
    let p_process_siginh = p!(c_process, "siginh")?;
    let p_process_signal = p!(c_process, "signal")?;
    let p_process_sigstop = p!(c_process, "sigstop")?;
    let p_process_transition = p!(c_process, "transition")?;

    let c_unix_stream_socket = c!("unix_stream_socket")?;
    let p_unix_stream_socket_connectto = p!(c_unix_stream_socket, "connectto")?;

    // Make msd_app a copy of untrusted_app.

    let t_source = t!(n_source_type)?;
    let t_source_uffd = t!(n_source_uffd_type)?;
    let t_target = pdb.create_type(n_target_type, false)?.0;
    let t_target_uffd = pdb.create_type(n_target_uffd_type, false)?.0;

    pdb.copy_roles(t_source, t_target)?;
    pdb.copy_roles(t_source_uffd, t_target_uffd)?;

    pdb.copy_attributes(t_source, t_target)?;
    pdb.copy_attributes(t_source_uffd, t_target_uffd)?;

    pdb.copy_constraints(t_source, t_target);
    pdb.copy_constraints(t_source_uffd, t_target_uffd);

    pdb.copy_avtab_rules(Box::new(move |source_type, target_type, class| {
        let mut new_source_type = None;
        let mut new_target_type = None;

        if source_type == t_source {
            new_source_type = Some(t_target);
        } else if source_type == t_source_uffd {
            new_source_type = Some(t_target_uffd);
        }

        if target_type == t_source {
            new_target_type = Some(t_target);
        } else if target_type == t_source_uffd {
            new_target_type = Some(t_target_uffd);
        }

        if new_source_type.is_none() && new_target_type.is_none() {
            None
        } else {
            Some((
                new_source_type.unwrap_or(source_type),
                new_target_type.unwrap_or(target_type),
                class,
            ))
        }
    }))?;

    // Create a new type for running the daemon.

    let t_daemon = pdb.create_type(n_daemon_type, false)?.0;
    pdb.add_to_role(r_r, t_daemon)?;
    pdb.set_attribute(t_daemon, t_domain, true)?;
    pdb.set_attribute(t_daemon, t_mlstrustedsubject, true)?;

    // Setting the `domain` attribute isn't sufficient to grab many of the
    // "standard" rules. These are defined in the sepolicy source with a target
    // type of `self`, which means they were expanded at compile time. Since we
    // no longer have a record of these rules, we use some heuristics to copy
    // them from an existing type.
    pdb.copy_avtab_rules(Box::new(move |source_type, target_type, class| {
        if source_type == t_hal_usb_gadget_default && target_type == t_hal_usb_gadget_default {
            Some((t_daemon, t_daemon, class))
        } else {
            None
        }
    }))?;

    // Allow executing the daemon binary.
    for perm in [p_file_entrypoint, p_file_execute, p_file_map, p_file_read] {
        pdb.set_rule(t_daemon, t_system_file, c_file, perm, RuleAction::Allow);
    }

    // Allow init to transition to the daemon domain.
    pdb.set_rule(
        t_init,
        t_daemon,
        c_process,
        p_process_transition,
        RuleAction::Allow,
    );

    // Don't allow disabling AT_SECURE.
    pdb.set_rule(
        t_init,
        t_daemon,
        c_process,
        p_process_noatsecure,
        RuleAction::Deny,
    );

    // Allow inheriting resource limits and signal state from parent process.
    for perm in [p_process_rlimitinh, p_process_siginh] {
        pdb.set_rule(t_init, t_daemon, c_process, perm, RuleAction::Allow);
    }

    // Allow the daemon to drop privileges.
    for perm in [p_capability_chown, p_capability_setgid, p_capability_setuid] {
        pdb.set_rule(t_daemon, t_daemon, c_capability, perm, RuleAction::Allow);
    }

    // Allow the daemon to read the SELinux status.
    for perm in [p_file_open, p_file_read] {
        pdb.set_rule(t_daemon, t_selinuxfs, c_file, perm, RuleAction::Allow);
    }

    // Allow the daemon to find (only) the USB gadget HAL in /proc.
    pdb.set_rule(
        t_daemon,
        t_hal_usb_gadget_impl,
        c_dir,
        p_dir_search,
        RuleAction::Allow,
    );
    pdb.set_rule(t_daemon, t_domain, c_file, p_file_read, RuleAction::Deny);
    pdb.set_rule(
        t_daemon,
        t_hal_usb_gadget_impl,
        c_file,
        p_file_read,
        RuleAction::Allow,
    );
    pdb.set_rule(
        t_daemon,
        t_hal_usb_gadget_impl,
        c_lnk_file,
        p_lnk_file_read,
        RuleAction::Allow,
    );

    // Allow the daemon to send SIGSTOP/SIGCONT to the USB gadget HAL.
    for perm in [p_process_sigstop, p_process_signal] {
        pdb.set_rule(
            t_daemon,
            t_hal_usb_gadget_impl,
            c_process,
            perm,
            RuleAction::Allow,
        );
    }

    // Allow the daemon to interact with configfs.
    for perm in [
        p_dir_add_name,
        p_dir_create,
        p_dir_open,
        p_dir_read,
        p_dir_remove_name,
        p_dir_rmdir,
        p_dir_search,
        p_dir_setattr,
        p_dir_write,
    ] {
        pdb.set_rule(t_daemon, t_configfs, c_dir, perm, RuleAction::Allow);
    }
    for perm in [p_file_create, p_file_open, p_file_setattr, p_file_write] {
        pdb.set_rule(t_daemon, t_configfs, c_file, perm, RuleAction::Allow);
    }
    for perm in [p_lnk_file_create, p_lnk_file_read, p_lnk_file_unlink] {
        pdb.set_rule(t_daemon, t_configfs, c_lnk_file, perm, RuleAction::Allow);
    }

    // Allow the daemon to read the sys.usb.controller property.
    for perm in [p_file_getattr, p_file_map, p_file_open, p_file_read] {
        pdb.set_rule(
            t_daemon,
            t_usb_control_prop,
            c_file,
            perm,
            RuleAction::Allow,
        );
    }

    // Allow the daemon to read files on FUSE filesystems. This also allows the
    // mass storage driver to access the files (it uses the daemon's context).
    for target in [
        // SAF authority: com.android.providers.downloads.documents.
        t_mediaprovider,
        // SAF authority: com.android.externalstorage.documents.
        t_mediaprovider_app,
    ] {
        pdb.set_rule(t_daemon, target, c_fd, p_fd_use, RuleAction::Allow);
    }
    for perm in [p_file_getattr, p_file_read, p_file_open, p_file_write] {
        pdb.set_rule(t_daemon, t_fuse, c_file, perm, RuleAction::Allow);
    }

    // Allow the kernel to use the daemon's FD.
    pdb.set_rule(t_kernel, t_daemon, c_fd, p_fd_use, RuleAction::Allow);

    // Block the daemon from connecting to itself. The daemon uses this to test
    // that the policy is loaded.
    pdb.set_rule(
        t_daemon,
        t_daemon,
        c_unix_stream_socket,
        p_unix_stream_socket_connectto,
        RuleAction::Deny,
    );

    // Allow the client to connect to daemon.
    pdb.set_rule(
        t_target,
        t_daemon,
        c_unix_stream_socket,
        p_unix_stream_socket_connectto,
        RuleAction::Allow,
    );

    // Unprivileged execution of `msd-tool client` is denied by default to
    // reduce the attack surface.
    if cli.allow_adb {
        // Allow the client to connect to the daemon.
        pdb.set_rule(
            t_shell,
            t_daemon,
            c_unix_stream_socket,
            p_unix_stream_socket_connectto,
            RuleAction::Allow,
        );

        // Allow the daemon to receive fds from the client.
        pdb.set_rule(t_daemon, t_shell, c_fd, p_fd_use, RuleAction::Allow);
    }

    if cli.strip_no_audit {
        pdb.strip_no_audit();
    }

    write_policy(cli.target.as_path(), &pdb)?;

    Ok(())
}

#[derive(Debug, Args)]
#[group(required = true, multiple = false)]
struct SourceGroup {
    /// Source policy file.
    #[arg(short, long, value_parser, value_name = "FILE")]
    source: Option<PathBuf>,

    /// Use currently loaded policy as source.
    #[arg(short = 'S', long)]
    source_kernel: bool,
}

impl SourceGroup {
    fn as_path(&self) -> &Path {
        if let Some(path) = &self.source {
            path
        } else if self.source_kernel {
            Path::new("/sys/fs/selinux/policy")
        } else {
            unreachable!()
        }
    }
}

#[derive(Debug, Args)]
#[group(required = true, multiple = false)]
struct TargetGroup {
    /// Target policy file.
    #[arg(short, long, value_parser, value_name = "FILE")]
    target: Option<PathBuf>,

    /// Load patched policy into kernel.
    #[arg(short = 'T', long)]
    target_kernel: bool,
}

impl TargetGroup {
    fn as_path(&self) -> &Path {
        if let Some(path) = &self.target {
            path
        } else if self.target_kernel {
            Path::new("/sys/fs/selinux/load")
        } else {
            unreachable!()
        }
    }
}

/// Patch SELinux policy file.
#[derive(Debug, Parser)]
pub struct SepatchCli {
    #[command(flatten)]
    source: SourceGroup,

    #[command(flatten)]
    target: TargetGroup,

    /// Remove dontaudit/dontauditxperm rules.
    #[arg(short = 'd', long)]
    strip_no_audit: bool,

    /// Allow connections from adb shell session.
    #[arg(long)]
    allow_adb: bool,
}
