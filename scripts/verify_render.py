"""Isolated Linux/WSL Render-image QA. Requires nilebank:render-local, Docker and OpenSSL."""
import os, pathlib, subprocess, tempfile, time, uuid, urllib.request, ssl, threading, http.server, http.client, json
ROOT=pathlib.Path(__file__).resolve().parents[1]
def run(args,**kw):
    r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw)
    if r.returncode: raise RuntimeError('QA subprocess failed (arguments suppressed)')
    return r.stdout.decode()
tag=uuid.uuid4().hex[:10]; network='render-qa-'+tag; db=network+'-db'; app=network+'-app'
created=[]
with tempfile.TemporaryDirectory(prefix='render-qa-') as tmp:
 d=pathlib.Path(tmp); password=uuid.uuid4().hex+uuid.uuid4().hex
 run(['docker','network','create',network]);
 try:
  run(['docker','run','--rm','-d','--network',network,'--label','nilebank.qa=isolated','--name',db,'-e','POSTGRES_USER=nilebankqa','-e','POSTGRES_PASSWORD='+password,'-e','POSTGRES_DB=qa','postgres:17-alpine']); created.append(db)
  for _ in range(60):
   if subprocess.run(['docker','exec',db,'pg_isready','-U','nilebankqa'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0: break
   time.sleep(1)
  else: raise RuntimeError('QA database readiness timed out')
  cert=d/'cert.pem'; key=d/'key.pem'
  run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'])
  # A local TLS edge for the secure-cookie smoke test; not a public deployment.
  class Proxy(http.server.BaseHTTPRequestHandler):
   def do_GET(self): self.forward()
   def do_POST(self): self.forward()
   def forward(self):
    try: self.proxy()
    except (ConnectionError, TimeoutError):
     # Readiness polling can disconnect before a cold JVM accepts connections.
     self.close_connection=True
   def proxy(self):
    c=http.client.HTTPConnection('127.0.0.1',self.server.upstream,timeout=30)
    body=self.rfile.read(int(self.headers.get('Content-Length','0')))
    headers={k:v for k,v in self.headers.items() if k.lower() not in ('connection','host')}
    headers['Host']='localhost'; c.request(self.command,self.path,body or None,headers)
    r=c.getresponse(); data=r.read(); self.send_response(r.status)
    for k,v in r.getheaders():
     if k.lower() not in ('transfer-encoding','connection','content-length'): self.send_header(k,v)
    self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data); c.close()
   def log_message(self,*a): pass
  server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Proxy)
  context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(cert,key)
  server.socket=context.wrap_socket(server.socket,server_side=True)
  origin='https://localhost:'+str(server.server_port)
  envfile=d/'app.env'; envfile.write_text('SPRING_PROFILES_ACTIVE=render\nPORT=10000\nAPP_PUBLIC_URL='+origin+'\nDB_URL=jdbc:postgresql://'+db+':5432/qa\nDB_USER=nilebankqa\nDB_PASSWORD='+password+'\nJAVA_TOOL_OPTIONS=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError\n'); envfile.chmod(0o600)
  run(['docker','run','--rm','-d','--name',app,'--label','nilebank.qa=isolated','--network',network,'--memory','512m','--cpus','1','--read-only','--tmpfs','/tmp:size=64m','--cap-drop','ALL','--security-opt','no-new-privileges','--user','10001','--env-file',str(envfile),'-p','127.0.0.1::10000','nilebank:render-local']); created.append(app)
  port=int(run(['docker','port',app,'10000']).strip().rsplit(':',1)[1]); server.upstream=port
  threading.Thread(target=server.serve_forever,daemon=True).start()
  try:
   for _ in range(150):
    try:
     if urllib.request.urlopen(origin+'/api/v1/health',context=ssl.create_default_context(cafile=str(cert)),timeout=3).status==200: break
    except Exception: pass
    time.sleep(1)
   else: raise RuntimeError('512MB app readiness timed out')
   env=os.environ.copy(); env['SSL_CERT_FILE']=str(cert)
   output=run(['python3',str(ROOT/'scripts/smoke.py'),origin],cwd=ROOT,env=env)
   print(output,flush=True)
   state=json.loads(run(['docker','inspect',app]))[0]['State']; assert state['Running'] and not state['OOMKilled']
   print('PASS Render profile under 512MB: secure TLS-edge session, banking smoke, no OOM',flush=True)
   print(run(['docker','stats',app,'--no-stream','--format','{{.MemUsage}}']),flush=True)
   migrations=run(['docker','exec',db,'psql','-U','nilebankqa','-d','qa','-Atc','SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL']).strip(); assert migrations=='18'
   print('PASS all 18 migrations applied to fresh QA database',flush=True)
  finally: server.shutdown();server.server_close()
 finally:
  for name in reversed(created): run(['docker','stop',name])
  run(['docker','network','rm',network])
