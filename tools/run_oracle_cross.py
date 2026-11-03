#!/usr/bin/env python3
"""Runs task-isolated tests; credentials are received on stdin and never saved."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[1]
ide=Path('/Applications/DataGrip.app/Contents')
base=Path.home()/'Library/Application Support/JetBrains/DataGrip2026.2/jdbc-drivers'
drivers=list(base.glob('MySQL ConnectorJ/9.5.0/**/*.jar'))+list(base.glob('PostgreSQL/42.7.3/**/*.jar'))+list(base.glob('Oracle/23.26.2.0/**/*.jar'))
cp=':'.join(map(str,[root/'build/classes',root/'build/test-classes',*drivers]))+':'+(root/'build/sdk-classpath.txt').read_text()
raise SystemExit(subprocess.run([str(ide/'jbr/Contents/Home/bin/java'),'-ea','-Doracle.jdbc.ReadTimeout=30000','-cp',cp,'com.tablebridge.OracleCrossIntegration',str(root/'build/mysql-integration/data-local'),str(root/'build/pg-integration/data'),str(root/'build/oracle-cross-result.txt')],input=sys.stdin.buffer.read(),timeout=600).returncode)
