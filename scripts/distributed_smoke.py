"""Extra distributed-domain checks; inherits all disposable-project safeguards from integration_smoke.py."""
import runpy
from pathlib import Path
import time,json,datetime as dt,subprocess
ctx=runpy.run_path(str(Path(__file__).with_name('integration_smoke.py')))
globals().update({k:v for k,v in ctx.items() if not k.startswith('__')})
def db(domain,statement):
    return container('mysql',['sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B "$1" -e "$2"','sh','smart_hotel_'+domain,statement])
def wait_until(label,condition,seconds=45):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if condition():check(label,True);return
        time.sleep(.5)
    raise AssertionError(label)
# Re-enable only the synthetic account created by this guarded run, then get a fresh session.
sql(f"UPDATE sys_user SET status='ACTIVE' WHERE username='{guest}'")
gt=login(guest,password+'new')
booking.__globals__['gt']=gt
options=request('/api/v1/payment/options',token=gt)['data']
check('isolated payment mode explicitly reports MOCK',options['mode']=='MOCK' and options['available'])
model=request('/api/v1/customer/ai/status',token=gt)['data']
check('unconfigured external model reports unavailable',model['available'] is False)
request('/api/v1/customer/ai/recommend','POST',dict(text='两位成人住一晚',roomCount=1),gt,expected=503)
check('unconfigured model refuses fabricated recommendation',True)
request('/api/v1/customer/stay/extend','POST',dict(orderId=order['orderId'],newCheckOutDate=tomorrow.isoformat()),gt,expected=404)
request('/api/v1/staff/stay/extend','POST',dict(orderId=order['orderId'],newCheckOutDate=tomorrow.isoformat()),st,expected=404)
check('renewal endpoints removed for customer and staff',True)
request(f"/api/v1/customer/reviews/orders/{order['orderId']}",'POST',dict(rating=5,content='Isolated distributed regression'),gt)
check('review stored by independent owner',db('review',f"SELECT COUNT(*) FROM order_review WHERE order_id={order['orderId']}")=='1')
request(f"/api/v1/customer/reviews/orders/{order['orderId']}",'POST',dict(rating=5,content='duplicate'),gt,expected=409)
check('duplicate review rejected',True)
request(f"/api/v1/staff/orders/{order['orderId']}/complete",'POST',token=st)
check('completion changes authoritative order',request(f"/api/v1/bookings/{order['orderId']}",token=gt)['data']['orderStatus']=='COMPLETED')
coupon=request('/api/v1/customer/checkin','POST',token=gt)['data']['coupons'][0]['id']
def coupon_booking():
    return request('/api/v1/bookings','POST',dict(roomTypeId=roomtype,roomCount=1,guestCount=1,couponId=coupon,requestId='domain_'+secrets.token_hex(12),checkInDate=today.isoformat(),checkOutDate=tomorrow.isoformat(),contactName='Synthetic Guest',contactPhone='138'+basephone[3:],guests=[dict(guestName='Synthetic Guest',phone='138'+basephone[3:],idType='PASSPORT',idNumber='SYNTHETIC001')]),gt)['data']
order=coupon_booking()
check('marketing owns coupon reservation',db('marketing',f'SELECT status FROM customer_coupon WHERE id={coupon}')=='RESERVED')
request(f"/api/v1/bookings/{order['orderId']}/cancel",'POST',token=gt)
wait_until('coupon released after cancellation event',lambda:db('marketing',f'SELECT status FROM customer_coupon WHERE id={coupon}')=='AVAILABLE')
order=coupon_booking()
request(f"/api/v1/bookings/{order['orderId']}/mock-pay",'POST',token=gt)
wait_until('coupon marked used after payment event',lambda:db('marketing',f'SELECT status FROM customer_coupon WHERE id={coupon}')=='USED')
rooms=request(f"/api/v1/staff/check-ins/orders/{order['orderId']}/available-rooms",token=st)['data']
request('/api/v1/staff/check-ins','POST',dict(orderId=order['orderId'],roomIds=[rooms[0]['roomId']],remark='isolated checkout'),st)
request('/api/v1/staff/check-outs','POST',dict(orderId=order['orderId'],remark='isolated checkout'),st)
wait_until('statistics catches up to checkout',lambda:db('statistics',f"SELECT order_status FROM order_snapshot WHERE order_id={order['orderId']}")=='CHECKED_OUT')
request('/api/v1/admin/statistics/refresh?date='+today.isoformat(),'POST',token=at)
check('independent statistics endpoint',bool(request('/api/v1/admin/statistics/daily?startDate='+today.isoformat()+'&endDate='+today.isoformat(),token=at)['data']))
if args.faults:
    try:
        subprocess.run(['docker','compose','-p',args.compose_project,'stop','statistics'],cwd=ROOT,check=True,capture_output=True)
        order=booking()
        request(f"/api/v1/bookings/{order['orderId']}/mock-pay",'POST',token=gt)
        check('statistics outage does not prevent authoritative payment',request(f"/api/v1/bookings/{order['orderId']}",token=gt)['data']['orderStatus']=='CONFIRMED')
        check('event intent retained while statistics unavailable',int(db('order',"SELECT COUNT(*) FROM domain_outbox WHERE destination='statistics' AND status='PENDING'"))>0)
    finally:
        subprocess.run(['docker','compose','-p',args.compose_project,'up','-d','--no-deps','--wait','--wait-timeout','150','statistics'],cwd=ROOT,check=True,capture_output=True)
    wait_until('statistics recovers without restarting producer',lambda:db('statistics',f"SELECT order_status FROM order_snapshot WHERE order_id={order['orderId']}")=='CONFIRMED')
    wait_until('retained event queue drains after receiver recovers',lambda:db('order',"SELECT COUNT(*) FROM domain_outbox WHERE destination='statistics' AND status<>'DELIVERED'")=='0')
report={'timestamp':dt.datetime.now().isoformat(),'passed':len(checks),'checks':checks,'externalPayment':False,'externalAI':False}
(ROOT/'.run/distributed-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(len(checks),'distributed checks passed')
