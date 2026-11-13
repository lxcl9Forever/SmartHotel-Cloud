"""Opt-in live regression against a disposable local Compose deployment.

Creates synthetic accounts/orders. Reads captcha answers with operator access to
the isolated Redis container; the application captcha path itself is not bypassed.
No third-party Python packages required. Never run against a real customer DB.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import secrets
import subprocess
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--compose-project', required=True)
parser.add_argument('--allow-test-data', action='store_true')
parser.add_argument('--faults', action='store_true', help='Also stop and recover isolated user/hotel/inventory containers.')
parser.add_argument('--url', default='http://127.0.0.1:8080')
args = parser.parse_args()
if not args.allow_test_data or not args.compose_project.startswith('smarthotel-cloud-verification'):
    parser.error('Only disposable smarthotel-cloud-verification* Compose projects are accepted; pass --allow-test-data.')
ROOT = Path(__file__).resolve().parent.parent
address = urllib.parse.urlsplit(args.url)
if address.scheme != 'http' or address.hostname not in ('127.0.0.1', 'localhost') or address.username or address.password or address.path not in ('', '/'):
    parser.error('Live regression accepts only a local isolated HTTP endpoint without credentials or a base path.')
published_ports = set()
for service, internal_port in [('frontend', '80'), ('gateway', '8080')]:
    result = subprocess.run(['docker', 'compose', '-p', args.compose_project, 'port', service, internal_port], cwd=ROOT, capture_output=True, text=True)
    if result.returncode == 0:
        for line in result.stdout.splitlines():
            try:
                published_ports.add(urllib.parse.urlsplit('http://' + line.strip()).port)
            except ValueError:
                pass
if (address.port or 80) not in published_ports:
    parser.error('The target URL must use a frontend/gateway port published by this isolated Compose project.')
checks = []

def check(label, condition):
    if not condition:
        raise AssertionError(label)
    checks.append(label)
    print('PASS', label)

def container(service, command):
    return subprocess.check_output(['docker','compose','-p',args.compose_project,'exec','-T',service,*command], cwd=ROOT, text=True, encoding='utf-8').strip()

def sql(statement):
    # Operator-only access is confined by the disposable Compose project guard.
    schema = 'smart_hotel_business'
    if any(table in statement for table in ('sys_user', 'sys_role')):
        schema = 'smart_hotel_user'
    elif 'room_inventory' in statement:
        schema = 'smart_hotel_inventory'
    elif 'room_type' in statement:
        schema = 'smart_hotel_hotel'
    return container('mysql',['sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B "$1" -e "$2"','sh',schema,statement])

def redis(*command):
    return container('redis',['sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli "$@"','sh',*command])

def request(path, method='GET', data=None, token=None, expected=200, headers=None):
    hdr={'Content-Type':'application/json', **(headers or {})}
    if token:
        hdr['Authorization']='Bearer '+token
    raw=None if data is None else json.dumps(data).encode()
    req=urllib.request.Request(args.url+path,data=raw,method=method,headers=hdr)
    try:
        with urllib.request.urlopen(req,timeout=20) as response:
            code, body=response.status,response.read().decode()
    except urllib.error.HTTPError as error:
        code, body=error.code,error.read().decode()
    if code!=expected:
        raise AssertionError(f'{method} {path} expected {expected}, got {code}: {body[:400]}')
    if not body:
        return None
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return body

suffix=secrets.token_hex(4)
password='Cloud-'+secrets.token_urlsafe(16)
admin='smoke_admin_'+suffix
guest='smoke_guest_'+suffix
staff='smoke_staff_'+suffix
basephone='139'+str(secrets.randbelow(10**8)).zfill(8)

def register(username,phone):
    request('/api/v1/auth/register','POST',dict(username=username,password=password,name='Synthetic Guest',phone=phone))

def login(username,pwd=password):
    captcha=request('/api/v1/auth/captcha')['data']
    solution=redis('GET','auth:captcha:'+captcha['captchaId'])
    return request('/api/v1/auth/login','POST',dict(username=username,password=pwd,captchaId=captcha['captchaId'],captchaCode=solution))['data']['token']

register(admin,basephone)
register(guest,'138'+basephone[3:])
sql(f"UPDATE sys_user SET role_id=(SELECT role_id FROM sys_role WHERE role_code='ADMIN') WHERE username='{admin}'")
at,gt=login(admin),login(guest)
check('registration, captcha and login through gateway', bool(at and gt))
check('user profile route',request('/api/v1/customer/profile',token=gt)['data']['username']==guest)
check('anonymous business read',request('/api/v1/hotel')['code']==200)
check('room-type business route',bool(request('/api/v1/room-types')['data']))
request('/api/v1/admin/users',token=gt,expected=403)
check('customer cannot access administrator routes',True)
request('/api/v1/bookings/my',expected=401,headers={'X-User-Role':'ADMIN','X-User-Id':'1'})
check('forged identity headers cannot authenticate',True)
request('/internal/v1/sessions/introspect','POST',{},expected=404)
check('internal endpoint is not published by gateway',True)
request('/api/v1/test/redis/inventory/init','POST',token=at)
today=dt.date.today()
tomorrow=today+dt.timedelta(days=1)
roomtype=int(sql('SELECT MIN(room_type_id) FROM room_type WHERE status=1'))
def inv():
    return int(redis('GET',f'hotel:inventory:{roomtype}:{today.isoformat()}'))

def booking():
    return request('/api/v1/bookings','POST',dict(roomTypeId=roomtype,roomCount=1,guestCount=1,
        requestId='smoke_'+secrets.token_hex(12),checkInDate=today.isoformat(),checkOutDate=tomorrow.isoformat(),contactName='Synthetic Guest',
        contactPhone='138'+basephone[3:],remark='Automated isolated regression',
        guests=[dict(guestName='Synthetic Guest',phone='138'+basephone[3:],idType='PASSPORT',idNumber='SYNTHETIC001')]),gt)['data']

before=inv()
order=booking()
check('booking traverses gateway and remote authentication',order['orderStatus']=='WAIT_PAY' and inv()==before-1)
request(f"/api/v1/bookings/{order['orderId']}/cancel",'POST',token=gt)
check('cancel releases MySQL and Redis reservation',inv()==before and sql(f"SELECT locked_qty FROM room_inventory WHERE room_type_id={roomtype} AND inventory_date='{today}'")=='0')
order=booking()
request(f"/api/v1/bookings/{order['orderId']}/mock-pay",'POST',token=gt)
check('explicit simulated payment confirms booking',request(f"/api/v1/bookings/{order['orderId']}",token=gt)['data']['orderStatus']=='CONFIRMED')
request('/api/v1/customer/refund/apply?'+urllib.parse.urlencode({'orderId':order['orderId'],'reason':'isolated regression'}),'POST',token=gt)
refund=int(sql(f"SELECT refund_id FROM refund_record WHERE order_id={int(order['orderId'])}"))
request(f'/api/v1/admin/refund/{refund}/approve','POST',token=at)
check('refund approval restores inventory',inv()==before)
check('administrator payment query remains in business service',request('/api/v1/admin/payments',token=at)['code']==200)
request('/api/v1/admin/employees','POST',dict(username=staff,password=password,employeeNo='T'+suffix,employeeName='Synthetic Staff',phone='137'+basephone[3:],email='',position='Test'),at)
st=login(staff)
order=booking()
request(f"/api/v1/bookings/{order['orderId']}/mock-pay",'POST',token=gt)
rooms=request(f"/api/v1/staff/check-ins/orders/{order['orderId']}/available-rooms",token=st)['data']
request('/api/v1/staff/check-ins','POST',dict(orderId=order['orderId'],roomIds=[rooms[0]['roomId']],remark='regression'),st)
request('/api/v1/staff/check-outs','POST',dict(orderId=order['orderId'],remark='regression'),st)
check('new staff account can check in and check out a paid booking',request(f"/api/v1/bookings/{order['orderId']}",token=gt)['data']['orderStatus']=='CHECKED_OUT')
request('/api/v1/customer/profile/password','PUT',dict(oldPassword=password,newPassword=password+'new'),gt)
request('/api/v1/bookings/my',token=gt,expected=401)
check('password change revokes token at remote business service',True)
gt=login(guest,password+'new')
request('/api/v1/bookings/my',token=gt)
check('new credential works across services',True)
if args.faults:
    search = '/api/v1/room-types/available?' + urllib.parse.urlencode(dict(checkInDate=today.isoformat(), checkOutDate=tomorrow.isoformat(), roomCount=1, guestCount=1))
    for service, path, auth in [('user', '/api/v1/bookings/my', gt), ('hotel', '/api/v1/room-types', None), ('inventory', search, None)]:
        request(path, token=auth)
        try:
            subprocess.run(['docker','compose','-p',args.compose_project,'stop',service], cwd=ROOT, check=True)
            request(path, token=auth, expected=503)
            check(service + ' outage fails closed with HTTP 503', True)
        finally:
            # Even a failed assertion must attempt to restore the test environment.
            subprocess.run(['docker','compose','-p',args.compose_project,'up','-d','--no-deps','--wait','--wait-timeout','150',service], cwd=ROOT, check=True)
        request(path, token=auth)
        check(service + ' recovers without restarting callers', True)

uid=int(sql(f"SELECT user_id FROM sys_user WHERE username='{guest}'"))
request(f'/api/v1/admin/users/{uid}/status?status=DISABLED','POST',token=at)
request('/api/v1/bookings/my',token=gt,expected=401)
check('account disable revokes remote access immediately',True)
report={'timestamp':dt.datetime.now().isoformat(),'environment':'isolated local MySQL 8.0.44 + Redis 7.4.7',
        'checks':checks,'passed':len(checks),'externalPayment':False,'externalAI':False}
out=ROOT/'.run'/'integration-report.json'
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'{len(checks)} checks passed; report: {out}')
