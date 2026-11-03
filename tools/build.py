#!/usr/bin/env python3
"""Offline build against an installed DataGrip 2026.2 SDK; no credentials or downloads."""
import argparse, json, os, pathlib, shutil, subprocess, zipfile
import xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(); p.add_argument('--ide', default=os.environ.get('DATAGRIP_HOME', '/Applications/DataGrip.app/Contents')); p.add_argument('--test', action='store_true'); args = p.parse_args()
ide = pathlib.Path(args.ide); info = json.loads((ide/'Resources/product-info.json').read_text())
if not info['buildNumber'].startswith('262.'): raise SystemExit('This alpha requires DataGrip 2026.2 (262.*).')
javac = ide/'jbr/Contents/Home/bin/javac'; java = javac.with_name('java')
if not javac.exists():
 javac = pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'; java=javac.with_name('java')
jars = list((ide/'lib').rglob('*.jar')) + list((ide/'plugins/DatabaseTools/lib').rglob('*.jar')) + list((ide/'plugins/grid-core-plugin/lib').rglob('*.jar'))
cp = os.pathsep.join(map(str,jars)); build=ROOT/'build'; classes=build/'classes'; classes.mkdir(parents=True,exist_ok=True)
for f in classes.rglob('*.class'): f.unlink()
sources = list((ROOT/'src/main/java').rglob('*.java'))
argfile=build/'javac.args';argfile.write_text('\n'.join('"'+str(x)+'"' for x in ['--release','21','-encoding','UTF-8','-classpath',cp,'-d',classes,*sources]))
result=subprocess.run([str(javac),'@'+str(argfile)],check=False)
if result.returncode: raise SystemExit(result.returncode)
for f in (ROOT/'src/main/resources').rglob('*'):
 if f.is_file():
  dest=classes/f.relative_to(ROOT/'src/main/resources'); dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
if args.test:
 tests=list((ROOT/'src/test/java').rglob('*.java')); testdir=build/'test-classes';testdir.mkdir(exist_ok=True)
 result=subprocess.run([str(javac),'--release','21','-encoding','UTF-8','-cp',str(classes)+os.pathsep+cp,'-d',str(testdir),*map(str,tests)],check=False)
 if result.returncode: raise SystemExit(result.returncode)
 result=subprocess.run([str(java),'-Dtablebridge.language=zh','-ea','-cp',str(testdir)+os.pathsep+str(classes)+os.pathsep+cp,'com.tablebridge.CoreTest'],check=False)
 if result.returncode: raise SystemExit(result.returncode)
lib=build/'tablebridge/lib';lib.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(lib/'tablebridge.jar','w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for f in sorted(classes.rglob('*')):
  if f.is_file(): z.write(f,str(f.relative_to(classes)))
version=ET.parse(ROOT/'src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
dist=ROOT/'dist';dist.mkdir(exist_ok=True)
with zipfile.ZipFile(dist/f'tablebridge-{version}.zip','w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:z.write(lib/'tablebridge.jar','tablebridge/lib/tablebridge.jar')
print('Built',dist/f'tablebridge-{version}.zip','against',info['version'])
