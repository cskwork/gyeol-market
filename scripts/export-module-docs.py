#!/usr/bin/env python3
"""Explicitly regenerate the checked-in module diagrams from the architecture test."""
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
subprocess.run([str(root / 'mvnw'), '-B', '-Dtest=ModuleArchitectureTest', *sys.argv[1:], 'test'], cwd=root, check=True)
source, destination = root / 'target/modulith-docs', root / 'docs/modules'
destination.mkdir(parents=True, exist_ok=True)
for previous in destination.iterdir():
    if previous.is_file() and previous.suffix in ('.adoc', '.puml'):
        previous.unlink()
shutil.copytree(source, destination, dirs_exist_ok=True)
print('Exported generated module documentation to docs/modules')
