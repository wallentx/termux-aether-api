"""Collect shipped dependency notices from the verified Go module cache in CI."""
import json
from pathlib import Path
import subprocess

text = subprocess.check_output(['go', 'list', '-m', '-json', 'all'], text=True)
decoder = json.JSONDecoder()
modules = []
while text.strip():
    module, end = decoder.raw_decode(text.lstrip())
    modules.append(module)
    text = text.lstrip()[end:]
root = Path(subprocess.check_output(['go', 'env', 'GOROOT'], text=True).strip())
print('Go standard library\n' + (root / 'LICENSE').read_text())
for module in modules:
    if module.get('Main') or not module.get('Dir'):
        continue
    directory = Path(module['Dir'])
    for file in sorted(directory.iterdir()):
        if file.is_file() and file.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE')):
            print(f'\n{module["Path"]} {module.get("Version", "")} - {file.name}\n')
            print(file.read_text(errors='replace'))
