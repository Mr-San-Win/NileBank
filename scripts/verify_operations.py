"""Isolated Linux/WSL checks; requires Docker, GPG and OpenSSL."""
import os, pathlib, subprocess, tempfile, time, uuid, sys, ssl, threading, http.server, shutil
ROOT=pathlib.Path(__file__).resolve().parents[1]
DOCKER=shutil.which('docker')
sys.path.insert(0,str(ROOT/'scripts'))
from check_health import check

def run(args, **kwargs):
    result = subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kwargs)
    if result.returncode:
        raise RuntimeError('Operational subprocess failed; sensitive arguments suppressed')
    return result

with tempfile.TemporaryDirectory(prefix='nilebank-ops-') as tmp:
    d=pathlib.Path(tmp); env=os.environ.copy(); env['GNUPGHOME']=str(d/'gpg'); (d/'gpg').mkdir(mode=0o700)
    run(['gpg','--batch','--pinentry-mode','loopback','--passphrase','','--quick-generate-key','Isolated QA <qa@example.invalid>','rsa2048','encr','1d'],env=env)
    keys=run(['gpg','--batch','--with-colons','--list-keys'],env=env).stdout.decode()
    fingerprint=next(line.split(':')[9] for line in keys.splitlines() if line.startswith('fpr:'))
    container='nilebank-ops-'+uuid.uuid4().hex[:10]
    run(['docker','run','-d','--rm','--label','nilebank.qa=isolated','--name',container,'-e','POSTGRES_PASSWORD='+uuid.uuid4().hex,'postgres:17-alpine'])
    try:
        for _ in range(60):
            if subprocess.run(['docker','exec',container,'pg_isready','-U','postgres'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0: break
            time.sleep(1)
        else: raise RuntimeError('QA database did not become ready')
        run(['docker','exec',container,'psql','-U','postgres','-c','CREATE TABLE qa_marker (value integer); INSERT INTO qa_marker VALUES (42);'])
        shim=d/'bin'; shim.mkdir(); docker=shim/'docker'
        docker.write_text("#!/bin/sh\nexec "+DOCKER+" exec -i "+container+" pg_dump -U postgres -d postgres --format=custom --no-owner --no-acl\n"); docker.chmod(0o700)
        # Avoid any existing Compose project: wrapper routes only into our new QA database.
        env['PATH']=str(shim)+':'+env['PATH']
        archive=d/'backup.gpg'
        run(['sh',str(ROOT/'scripts/backup_database.sh'),fingerprint,str(archive)],cwd=ROOT,env=env)
        assert archive.stat().st_mode & 0o077 == 0
        dump=run(['gpg','--batch','--decrypt',str(archive)],env=env).stdout
        run(['docker','exec',container,'createdb','-U','postgres','qa_restored'])
        run(['docker','exec','-i',container,'pg_restore','-U','postgres','-d','qa_restored','--exit-on-error'],input=dump)
        value=run(['docker','exec',container,'psql','-U','postgres','-d','qa_restored','-Atc','SELECT value FROM qa_marker']).stdout.strip()
        assert value==b'42'
        assert subprocess.run(['sh',str(ROOT/'scripts/backup_database.sh'),fingerprint,str(archive)],cwd=ROOT,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
        corrupt=bytearray(archive.read_bytes()); corrupt[len(corrupt)//2]^=1; (d/'corrupt.gpg').write_bytes(corrupt)
        assert subprocess.run(['gpg','--batch','--decrypt',str(d/'corrupt.gpg')],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
        docker.write_text('#!/bin/sh\nexit 9\n')
        assert subprocess.run(['sh',str(ROOT/'scripts/backup_database.sh'),fingerprint,str(d/'failed.gpg')],cwd=ROOT,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
        assert not (d/'failed.gpg').exists()
        print('PASS encrypted PostgreSQL dump/restore, private permissions, overwrite refusal, tamper rejection and dump-failure cleanup',flush=True)
    finally:
        run(['docker','stop',container])
    cert=d/'fullchain.pem'; key=d/'privkey.pem'
    run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'])
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200); self.end_headers(); self.wfile.write(b'{"status":"UP"}')
        def log_message(self,*args): pass
    server=http.server.HTTPServer(('127.0.0.1',0),Handler)
    ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(cert,key); server.socket=ctx.wrap_socket(server.socket,server_side=True)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    origin='https://localhost:'+str(server.server_port)
    try:
        assert check(origin,ca_file=str(cert))=={'status':'UP'}
        try: check(origin)
        except Exception: pass
        else: raise AssertionError('Untrusted certificate accepted')
    finally: server.shutdown(); server.server_close()
    print('PASS real HTTPS readiness with trusted QA CA; untrusted certificate rejected',flush=True)
    config=d/'nginx.conf'; config.write_text('events {}\nhttp { include /etc/nginx/nilebank.conf; }\n')
    run(['docker','run','--rm','-v',str(config)+':/etc/nginx/nginx.conf:ro','-v',str(ROOT/'infra/nginx/nilebank.conf')+':/etc/nginx/nilebank.conf:ro','-v',str(d)+':/etc/nginx/tls:ro','nginx:stable-alpine','nginx','-t'])
    print('PASS Nginx TLS/rate-limit/forward-header template syntax with actual certificate files',flush=True)
