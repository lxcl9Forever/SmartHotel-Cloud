"""Marketing reservation/race acceptance; inherits the disposable Compose guard."""
import runpy,subprocess,json,uuid,time,datetime as dt
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
ctx=runpy.run_path(str(Path(__file__).with_name('integration_smoke.py')))
globals().update({k:v for k,v in ctx.items() if not k.startswith('__')})
sql(f"UPDATE sys_user SET status='ACTIVE' WHERE username='{guest}'")
gt=login(guest,password+'new')
def db(domain,statement):return container('mysql',['sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B "$1" -e "$2"','sh','smart_hotel_'+domain,statement])
def wait_for(label,condition):
    end=time.monotonic()+45
    while time.monotonic()<end:
        if condition():check(label,True);return
        time.sleep(.5)
    raise AssertionError(label)
def internal(path,payload,expected=200):
    command='curl -sS --max-time 15 -w "\n%{http_code}" -H "Content-Type: application/json" -H "X-Internal-Token: $INTERNAL_TOKEN" --data-binary "$1" "http://127.0.0.1:8086$2"'
    raw=container('marketing',['sh','-c',command,'sh',json.dumps(payload),path])
    body,status=raw.rsplit('\n',1)
    if int(status)!=expected:raise AssertionError(f'Marketing protocol expected {expected}, got {status}: {body[:200]}')
    return json.loads(body)
start=(today+dt.timedelta(days=2)).isoformat();until=(today+dt.timedelta(days=3)).isoformat()
now=dt.datetime.now();rules=dict(roomTypeId=roomtype,checkInDate=start,checkOutDate=until,startTime=(now-dt.timedelta(minutes=1)).isoformat(),endTime=(now+dt.timedelta(minutes=30)).isoformat(),quota=1,discountRate=.5)
deal=request('/api/v1/admin/daily-deals','POST',rules,at)['data']['id']
request(f'/api/v1/admin/daily-deals/{deal}/enabled?enabled=true','PUT',token=at)
customer=int(db('user',f"SELECT customer_id FROM customer WHERE user_id=(SELECT user_id FROM sys_user WHERE username='{guest}')"))
def command(op):return dict(operationId=op,customerId=customer,originalAmount=688,request=dict(roomTypeId=roomtype,roomCount=1,guestCount=1,dailyDealId=deal,checkInDate=start,checkOutDate=until))
op=str(uuid.uuid4());prepare='/internal/v1/marketing/reservations/prepare';decide='/internal/v1/marketing/reservations/decide'
internal(prepare,command(op));check('marketing Try holds one quota',db('marketing',f"SELECT state FROM marketing_reservation WHERE operation_id='{op}'")=='PREPARED')
request(f'/api/v1/admin/daily-deals/{deal}','PUT',{**rules,'quota':2},at,expected=409)
check('prepared quote prevents concurrent rule editing',True)
internal(decide,dict(operationId=op,decision='ABORT',orderId=None))
internal(prepare,command(op),expected=409);check('late Try cannot resurrect aborted reservation',True)
internal(decide,dict(operationId=op,decision='ABORT',orderId=None));check('repeated abort is idempotent',True)
op=str(uuid.uuid4());internal(decide,dict(operationId=op,decision='ABORT',orderId=None));internal(prepare,command(op),expected=409);check('abort-before-Try leaves a durable tombstone',True)
def competing_booking(_):
    data=dict(roomTypeId=roomtype,roomCount=1,guestCount=1,dailyDealId=deal,requestId='race_'+uuid.uuid4().hex,checkInDate=start,checkOutDate=until,contactName='Synthetic race',contactPhone='138'+basephone[3:],guests=[dict(guestName='Synthetic race',phone='138'+basephone[3:],idType='PASSPORT',idNumber='SYNTHETIC-RACE')])
    try:return request('/api/v1/bookings','POST',data,gt)['data']
    except AssertionError as failure:
        if 'got 409:' in str(failure):return None
        raise
with ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(competing_booking,range(4)))
winners=[o for o in results if o is not None]
check('four concurrent requests yield exactly one quota winner',len(winners)==1)
check('marketing quota not oversold',db('marketing',f"SELECT COALESCE(SUM(room_count),0) FROM marketing_reservation WHERE deal_id={deal} AND state IN ('PREPARED','COMMITTED') AND released=0")=='1')
winner=winners[0];request(f"/api/v1/bookings/{winner['orderId']}/cancel",'POST',token=gt)
wait_for('cancellation releases campaign quota',lambda:db('marketing',f"SELECT COALESCE(SUM(room_count),0) FROM marketing_reservation WHERE deal_id={deal} AND state IN ('PREPARED','COMMITTED') AND released=0")=='0')
second=competing_booking(0);check('released quota can be booked again',second is not None)
request(f"/api/v1/bookings/{second['orderId']}/cancel",'POST',token=gt)
wait_for('all marketing handoff decisions finish',lambda:db('order',"SELECT COUNT(*) FROM marketing_tcc_journal WHERE done=0")=='0')
report={'passed':len(checks),'checks':checks,'environment':'disposable Compose only','concurrentRequests':4,'externalPayment':False}
(ROOT/'.run/marketing-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(len(checks),'marketing checks passed')
