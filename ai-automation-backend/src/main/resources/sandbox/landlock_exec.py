"""Runs a command that cannot read, write, replace or remove the given paths (Linux Landlock, no root).

Arguments: --hide PATH [--hide PATH ...] -- COMMAND [ARGS...]

The backend passes this file as `python3 -I -S -c <code>`, so no copy of it lies on disk where a sandboxed
process could change it before the next start (ProcessSandbox, TODO 9525820a).

Landlock knows only allow rules, so the hidden paths are cut out by granting the complement: every
directory on the way to a hidden path may only be listed, every entry off that way gets all rights.
Rights on a directory reach everything below it, so a way directory must not get more than listing:
removing, renaming into it or creating a symlink there would let a process replace a hidden file. The
price is that no new file can be created directly in such a directory (e.g. the home directory itself);
its existing entries and every subdirectory off the way stay fully usable. A file that appears there
later is not readable either, which keeps a new key file or database journal hidden as well.

A hidden directory hides everything below it; a hidden path below another one is dropped, since as a way
directory the outer one would be opened up again. A symbolic link off the way gets no rule: opening it
would grant its target, and a link to a hidden path or a way directory would undo the hiding. Landlock
checks an access through a link where the link points to, so the link itself needs none (TODO 75890a40).

Exit code 126 and a line starting with "landlock_exec:" when the sandbox cannot be set up; the command
does not run then.
"""
import ctypes
import os
import stat
import sys

SYS_CREATE_RULESET, SYS_ADD_RULE, SYS_RESTRICT_SELF = 444, 445, 446
CREATE_RULESET_VERSION = 1
RULE_PATH_BENEATH = 1
PR_SET_NO_NEW_PRIVS = 38

READ_DIR = 1 << 3
REFER, TRUNCATE, IOCTL_DEV = 1 << 13, 1 << 14, 1 << 15
# Rights that apply to a file, not a directory: execute, write, read, truncate, ioctl on devices.
FILE_RIGHTS = (1 << 0) | (1 << 1) | (1 << 2) | TRUNCATE | IOCTL_DEV


class RulesetAttr(ctypes.Structure):
    _fields_ = [("handled_access_fs", ctypes.c_uint64), ("handled_access_net", ctypes.c_uint64),
                ("scoped", ctypes.c_uint64)]


class PathBeneathAttr(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("allowed_access", ctypes.c_uint64), ("parent_fd", ctypes.c_int32)]


def fail(message):
    sys.stderr.write("landlock_exec: %s\n" % message)
    sys.stderr.flush()
    os._exit(126)


def parse(argv):
    hidden, i = [], 0
    while i < len(argv) and argv[i] != "--":
        if argv[i] == "--hide" and i + 1 < len(argv):
            hidden.append(os.path.realpath(argv[i + 1]))
            i += 2
        else:
            fail("unexpected argument %r" % argv[i])
    command = argv[i + 1:]
    if not hidden or not command:
        fail("usage: --hide PATH [--hide PATH ...] -- COMMAND [ARGS...]")
    return outermost(hidden), command


def outermost(paths):
    """The hidden paths without those below another hidden one (sorted: a parent comes before its children)."""
    kept = []
    for path in sorted(set(paths)):
        if not any(path == k or path.startswith(k.rstrip("/") + "/") for k in kept):
            kept.append(path)
    return kept


def handled_rights(abi):
    rights = (1 << 13) - 1  # ABI 1: execute … make_sym
    if abi >= 2:
        rights |= REFER
    if abi >= 3:
        rights |= TRUNCATE
    if abi >= 5:
        rights |= IOCTL_DEV
    return rights


def main(argv):
    hidden, command = parse(argv)
    libc = ctypes.CDLL(None, use_errno=True)
    abi = libc.syscall(SYS_CREATE_RULESET, None, ctypes.c_size_t(0), ctypes.c_uint32(CREATE_RULESET_VERSION))
    if abi < 1:
        fail("Landlock is not available on this host (errno %d)" % ctypes.get_errno())
    rights = handled_rights(abi)
    attr = RulesetAttr(rights, 0, 0)
    size = ctypes.sizeof(RulesetAttr) if abi >= 4 else 8  # the net and scope fields came with ABI 4 and 6
    ruleset = libc.syscall(SYS_CREATE_RULESET, ctypes.byref(attr), ctypes.c_size_t(size), ctypes.c_uint32(0))
    if ruleset < 0:
        fail("cannot create the ruleset (errno %d)" % ctypes.get_errno())

    def grant(path, allowed):
        try:
            fd = os.open(path, os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC)
        except OSError:
            return  # vanished or unreadable entry: nothing to grant
        try:
            if not stat.S_ISDIR(os.fstat(fd).st_mode):
                allowed &= FILE_RIGHTS
            rule = PathBeneathAttr(allowed & rights, fd)
            if libc.syscall(SYS_ADD_RULE, ruleset, RULE_PATH_BENEATH, ctypes.byref(rule), ctypes.c_uint32(0)) != 0:
                fail("cannot add a rule for %s (errno %d)" % (path, ctypes.get_errno()))
        finally:
            os.close(fd)

    way = set()
    for path in hidden:
        parent = os.path.dirname(path)
        while parent not in way:
            way.add(parent)
            if parent == "/":
                break
            parent = os.path.dirname(parent)
    for directory in sorted(way):
        grant(directory, READ_DIR)
        try:
            entries = os.listdir(directory)
        except OSError:
            continue
        for entry in entries:
            path = os.path.join(directory, entry)
            if path not in hidden and path not in way and not os.path.islink(path):
                grant(path, rights)

    if libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0:
        fail("cannot set no_new_privs (errno %d)" % ctypes.get_errno())
    if libc.syscall(SYS_RESTRICT_SELF, ruleset, ctypes.c_uint32(0)) != 0:
        fail("cannot restrict the process (errno %d)" % ctypes.get_errno())
    os.close(ruleset)
    try:
        os.execvp(command[0], command)
    except OSError as e:
        sys.stderr.write("landlock_exec: cannot start %s: %s\n" % (command[0], e.strerror))
        sys.stderr.flush()
        os._exit(127)


main(sys.argv[1:])
