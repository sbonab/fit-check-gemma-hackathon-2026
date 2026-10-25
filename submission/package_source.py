"""Export only Fit Check source and reviewed submission assets to a clean repo."""
from pathlib import Path
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
destination = root / 'tmp/submission-repo'
destination.mkdir(parents=True, exist_ok=True)
ignored = shutil.ignore_patterns('build', '.gradle', '.idea', 'local.properties', '*.iml', '__pycache__')
for directory in ['android', 'scripts', 'docs']:
    shutil.copytree(root / directory, destination / directory, dirs_exist_ok=True, ignore=ignored)
(destination / 'eval').mkdir(exist_ok=True)
shutil.copy2(root / 'eval/README.md', destination / 'eval/README.md')
shutil.copytree(root / 'submission', destination / 'submission', dirs_exist_ok=True, ignore=ignored)
(destination / 'output/pdf').mkdir(parents=True, exist_ok=True)
shutil.copy2(root / 'output/pdf/fit-check-pitch.pdf', destination / 'output/pdf/fit-check-pitch.pdf')
shutil.copy2(root / 'submission/repository-README.md', destination / 'README.md')
shutil.copy2(root / '.gitignore', destination / '.gitignore')
shutil.copy2(root / '.gitattributes', destination / '.gitattributes')
with zipfile.ZipFile(root / 'output/fit-check-source.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(destination.rglob('*')):
        if p.is_file() and '.git' not in p.relative_to(destination).parts:
            z.write(p, 'fit-check/' + str(p.relative_to(destination)))
if not (destination / '.git').exists():
    subprocess.run(['git','init','-b','main'],cwd=destination,check=True)
subprocess.run(['git','add','.'],cwd=destination,check=True)
if subprocess.run(['git','diff','--cached','--quiet'],cwd=destination).returncode:
    subprocess.run(['git','commit','-m','Publish Fit Check hackathon draft with source and pitch'],cwd=destination,check=True)
print(destination)
