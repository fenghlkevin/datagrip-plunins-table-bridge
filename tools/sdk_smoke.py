#!/usr/bin/env python3
"""Prepare a separate IDE installation profile with an in-memory SDK test service."""
from pathlib import Path
import argparse, zipfile, os, signal, subprocess, time
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--stop',action='store_true');args=p.parse_args()
if args.stop:
 for name in ['sandbox','sdk-smoke']:
  file=root/'build'/name/'system/.pid'
  if not file.exists():continue
  pid=int(file.read_text());r=subprocess.run(['ps','-p',str(pid),'-o','comm='],capture_output=True,text=True)
  if r.stdout.strip()=='/Applications/DataGrip.app/Contents/MacOS/datagrip':
   os.kill(pid,signal.SIGTERM);print('Stopped isolated instance',pid)
 time.sleep(2)
else:
 sandbox=root/'build/sdk-smoke';sandbox.mkdir(parents=True,exist_ok=True)
 for d in ['config','system','plugins/tablebridge/lib','log']:(sandbox/d).mkdir(parents=True,exist_ok=True)
 (sandbox/'idea.properties').write_text('\n'.join('idea.'+k+'.path='+str(sandbox/v) for k,v in [('config','config'),('system','system'),('plugins','plugins'),('log','log')])+'\nidea.initially.ask.config=false\n')
 base=Path('/Applications/DataGrip.app/Contents/bin/datagrip.vmoptions').read_text()
 (sandbox/'datagrip.vmoptions').write_text('\n'.join(x for x in base.splitlines() if not x.startswith('-javaagent:'))+'\n-Dtablebridge.language='+os.environ.get('TABLEBRIDGE_LANGUAGE','zh')+'\n-Dtablebridge.smoke.output='+str(sandbox/'result.txt')+'\n')
 with zipfile.ZipFile(root/'build/tablebridge/lib/tablebridge.jar') as src,zipfile.ZipFile(sandbox/'plugins/tablebridge/lib/tablebridge.jar','w') as dst:
  for n in src.namelist():
   data=src.read(n)
   if n=='META-INF/plugin.xml':data=data.decode().replace('<actionPromoter','<applicationService serviceImplementation="com.tablebridge.SdkSmoke" preload="true"/>\n    <actionPromoter').encode()
   dst.writestr(n,data)
  for f in (root/'build/test-classes/com/tablebridge').glob('*.class'):
   if f.name.startswith(('SdkSmoke','UiSmoke','PlanSmoke','SyncPreviewSmoke')):dst.write(f,'com/tablebridge/'+f.name)
 print('Prepared',sandbox)
