"""Bundle the JNI library and its non-system dylibs with relocatable load commands."""
import argparse
import pathlib
import shutil
import subprocess

def output(*args):
    return subprocess.check_output(args, text=True)

def dependencies(path):
    return [line.strip().split(" (", 1)[0] for line in output("otool", "-L", str(path)).splitlines()[1:]]

def resolve(name, source):
    if name.startswith("@loader_path/"):
        return source.parent / name[len("@loader_path/"):]
    if name.startswith("@rpath/"):
        lines = output("otool", "-l", str(source)).splitlines()
        for index, line in enumerate(lines):
            if line.strip() == "cmd LC_RPATH":
                value = lines[index + 2].strip().split("path ", 1)[1].split(" (offset", 1)[0]
                candidate = pathlib.Path(value.replace("@loader_path", str(source.parent))) / name[len("@rpath/"):]
                if candidate.exists():
                    return candidate
        raise RuntimeError("Cannot resolve " + name + " from " + str(source))
    return pathlib.Path(name)

parser = argparse.ArgumentParser()
parser.add_argument("source")
parser.add_argument("destination")
args = parser.parse_args()
source = pathlib.Path(args.source).resolve()
destination = pathlib.Path(args.destination)
architectures = {"macos-aarch64": "arm64", "macos-x86_64": "x86_64"}
expected = architectures.get(destination.name)
if not expected or output("lipo", "-archs", str(source)).strip() != expected:
    raise RuntimeError("Native classifier does not match the compiled library architecture")
if destination.exists():
    shutil.rmtree(destination)
destination.mkdir(parents=True)
copied = {}
pending = [source]
while pending:
    current = pending.pop().resolve()
    if current in copied:
        continue
    target = destination / current.name
    if target.name in {path.name for path in copied.values()}:
        raise RuntimeError("Conflicting native filenames: " + current.name)
    shutil.copy2(current, target)
    target.chmod(0o755)
    copied[current] = target
    for dependency in dependencies(current)[1:]:
        if dependency.startswith(("/usr/lib/", "/System/Library/")):
            continue
        pending.append(resolve(dependency, current))
for current, target in copied.items():
    subprocess.check_call(["install_name_tool", "-id", "@loader_path/" + target.name, str(target)])
    for dependency in dependencies(current)[1:]:
        if dependency.startswith(("/usr/lib/", "/System/Library/")):
            continue
        resolved = resolve(dependency, current).resolve()
        subprocess.check_call(["install_name_tool", "-change", dependency,
                               "@loader_path/" + copied[resolved].name, str(target)])
    subprocess.check_call(["codesign", "--force", "--sign", "-", str(target)])
(destination / "libraries.txt").write_text("\n".join(path.name for path in copied.values()) + "\n")
