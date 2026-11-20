<template>

  <div class="detail-page">

    <div class="detail-container">

      <div class="page-header">

        <el-button @click="router.push('/home')">
          返回首页
        </el-button>

        <h1>订单详情</h1>

      </div>


      <el-card
          v-loading="loading"
          v-if="order"
      >

        <el-alert v-if="order.processing" :title="order.processingMessage || '订单正在处理中，请稍后刷新查看结果'" type="info" :closable="false" style="margin-bottom: 16px" />
          <el-descriptions
            title="预订信息"
            :column="2"
            border
        >

          <el-descriptions-item label="订单编号">
            {{ order.orderNo }}
          </el-descriptions-item>

          <el-descriptions-item label="订单状态">
            <el-tag>
              {{ orderStatusText[order.orderStatus] || order.orderStatus }}
            </el-tag>
          </el-descriptions-item>

          <el-descriptions-item label="房型">
            {{ order.roomTypeName }}
          </el-descriptions-item>

          <el-descriptions-item label="房间数量">
            {{ order.roomCount }} 间
          </el-descriptions-item>

          <el-descriptions-item label="入住日期">
            {{ order.checkInDate }}
          </el-descriptions-item>

          <el-descriptions-item label="退房日期">
            {{ order.checkOutDate }}
          </el-descriptions-item>

          <el-descriptions-item label="入住晚数">
            {{ order.nights }} 晚
          </el-descriptions-item>

          <el-descriptions-item label="入住人数">
            {{ order.guestCount }} 人
          </el-descriptions-item>

          <el-descriptions-item label="联系人">
            {{ order.contactName }}
          </el-descriptions-item>

          <el-descriptions-item label="联系电话">
            {{ order.contactPhone }}
          </el-descriptions-item>

          <el-descriptions-item label="支付状态">
            {{ paymentStatusText[order.paymentStatus] || order.paymentStatus }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.orderStatus === 'WAIT_PAY' && order.expireTime" label="支付倒计时">
            <PaymentCountdown :expire-time="order.expireTime" />
          </el-descriptions-item>
          <el-descriptions-item v-if="order.refundStatus" label="退款申请状态">
            {{ refundStatusText[order.refundStatus] || order.refundStatus }}
          </el-descriptions-item>

          <el-descriptions-item label="订单金额">
            <strong class="price">
              ¥{{ order.totalAmount }}
            </strong>
          </el-descriptions-item>
          <el-descriptions-item v-if="Number(order.discountAmount) > 0" label="优惠金额">
            -¥{{ order.discountAmount }}（原价 ¥{{ order.originalAmount }}）
          </el-descriptions-item>
          <el-descriptions-item v-if="order.dailyDealId" label="优惠类型">
            每日优惠 · 限量秒杀（不叠加其他优惠）
          </el-descriptions-item>
          <el-descriptions-item v-if="Number(order.couponDiscountAmount) > 0" label="其中优惠券抵扣">
            -¥{{ order.couponDiscountAmount }}
          </el-descriptions-item>

        </el-descriptions>

        <el-divider content-position="left">
          入住人信息
        </el-divider>

        <el-table
            v-if="order.guests && order.guests.length > 0"
            :data="order.guests"
            border
            style="width: 100%"
        >

          <el-table-column
              label="入住人"
              min-width="120"
          >
            <template #default="{ row }">

      <span>
        {{ row.guestName }}
      </span>

              <el-tag
                  v-if="row.isPrimary === 1"
                  type="success"
                  size="small"
                  style="margin-left: 8px"
              >
                主要入住人
              </el-tag>

            </template>
          </el-table-column>


          <el-table-column
              prop="phone"
              label="手机号"
              min-width="140"
          />


          <el-table-column
              label="证件类型"
              min-width="110"
          >
            <template #default="{ row }">
              {{ getIdTypeText(row.idType) }}
            </template>
          </el-table-column>


          <el-table-column
              label="证件号码"
              min-width="180"
          >
            <template #default="{ row }">
              {{ maskIdNumber(row.idNumber) }}
            </template>
          </el-table-column>

        </el-table>

        <div class="actions">




          <el-button
              v-if="order.orderStatus==='CONFIRMED' && order.paymentStatus==='PAID' && Number(order.totalAmount) > 0 && (!order.refundStatus || order.refundStatus==='REJECTED' || order.refundStatus==='REJECT')"
              type="danger"
              @click="openRefundDialog"
          >
            {{ order.refundStatus === 'REJECTED' || order.refundStatus === 'REJECT' ? '重新申请退款' : '申请退款' }}
          </el-button>

          <el-button
              v-if="order.orderStatus === 'WAIT_PAY' && !order.processing"
              type="danger"
              plain
              @click="handleCancel"
          >
            取消订单
          </el-button>


          <el-button
              v-if="order.orderStatus === 'WAIT_PAY' && !order.processing"
              type="primary"
              :loading="paying"
              :disabled="!paymentOptions.available || paying"
              @click="handlePay"
          >
            {{ alipayEnabled ? '支付宝沙箱支付' : paymentOptions.mode === 'MOCK' ? '开发模拟支付' : '支付暂不可用' }}
          </el-button>

          <el-button
              v-if="alipayEnabled && order.orderStatus === 'WAIT_PAY'"
              :loading="queryingPayment"
              @click="handleQueryPayment"
          >
            我已完成付款，查询支付结果
          </el-button>


        </div>

      </el-card>

      <el-alert v-if="order?.orderStatus === 'WAIT_PAY'" :title="paymentOptions.message" :type="paymentOptions.available ? 'info' : 'warning'" :closable="false" style="margin: 16px 0" />
      <CustomerReviewPanel v-if="order" :order-id="order.orderId" :order-status="order.orderStatus" />

    </div>

  </div>



  <el-dialog
      title="申请退款"
      v-model="refundDialogVisible"
  >


    <el-input
        v-model="refundReason"
        placeholder="请输入退款原因"
    />


    <template #footer>

      <el-button
          @click="refundDialogVisible=false"
      >
        取消
      </el-button>


      <el-button
          type="danger"
          :loading="refunding"
          :disabled="!refundReason.trim()"
          @click="submitRefund"
      >
        确认退款
      </el-button>


    </template>


  </el-dialog>

</template>

<script setup>

import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElMessage,
  ElMessageBox
} from 'element-plus'

import {
  getBookingDetail,
  cancelBooking,
  mockPayBooking,
  createAlipayPage,
  queryAlipayPayment,
  getPaymentOptions,

} from '../api/booking'

import request from '../api/request'
import CustomerReviewPanel from '../components/CustomerReviewPanel.vue'
import PaymentCountdown from '../components/PaymentCountdown.vue'

const route = useRoute()
const router = useRouter()

const orderStatusText = {WAIT_PAY:'待支付',CONFIRMED:'已确认',CHECKED_IN:'已入住',CHECKED_OUT:'已退房',COMPLETED:'已完成',CANCELLED:'已取消',CLOSED:'已关闭',REFUNDED:'已退款',NO_SHOW:'未入住'}
const paymentStatusText = {UNPAID:'未支付',PAID:'已支付',REFUNDING:'退款中',REFUNDED:'已退款'}
const loading = ref(false)
const paymentOptions = ref({mode: 'NONE', available: false, message: '正在读取支付渠道配置'})
const alipayEnabled = computed(() => paymentOptions.value.mode === 'ALIPAY_SANDBOX')
const paying = ref(false)
const loadPaymentOptions = async () => {
  try { const { data } = await getPaymentOptions(); paymentOptions.value = data.data }
  catch (error) { paymentOptions.value = {mode: 'NONE', available: false, message: error.response?.data?.message || '支付配置读取失败，请刷新重试'} }
}
const order = ref(null)
const queryingPayment = ref(false)


const refundDialogVisible = ref(false)

const refundReason = ref('')
const refunding = ref(false)
const refundStatusText = {
  PENDING: '待审核',
  APPROVED: '已同意',
  REJECTED: '已拒绝',
  SUCCESS: '已退款',
  REJECT: '已拒绝'
}

const loadOrder = async () => {

  try {

    loading.value = true

    const response =
        await getBookingDetail(
            route.params.orderId
        )

    const result = response.data

    if (result.code !== 200) {
      ElMessage.error(result.message)
      return
    }

    order.value = result.data

  } catch (error) {

    ElMessage.error(
        error.response?.data?.message ||
        '订单加载失败'
    )

  } finally {

    loading.value = false
  }
}


const handleCancel = async () => {

  try {

    await ElMessageBox.confirm(
        '确定取消这个订单吗？',
        '取消订单',
        {
          confirmButtonText: '是',
          cancelButtonText: '否',
          type: 'warning'
        }
    )

    await cancelBooking(order.value.orderId)

    ElMessage.success('订单已取消')

    await loadOrder()

  } catch (error) {

    if (error === 'cancel') {
      return
    }

    ElMessage.error(
        error.response?.data?.message ||
        '取消订单失败'
    )
  }
}


const handlePay = async () => {
  if (!paymentOptions.value.available || paying.value || order.value?.processing) return


  if (alipayEnabled) {
    const paymentWindow = window.open('', '_blank')
    if (!paymentWindow) {
      ElMessage.error('浏览器阻止了支付窗口，请允许弹出窗口后重试')
      return
    }
    paying.value = true
    try {
      const response = await createAlipayPage(order.value.orderId)
      paymentWindow.opener = null
      paymentWindow.document.open()
      paymentWindow.document.write(response.data.data.formHtml)
      paymentWindow.document.close()
      ElMessage.info('请在支付宝沙箱页面完成支付，再返回本页点击“查询支付结果”')
    } catch (error) {
      paymentWindow.close()
      ElMessage.error(error.response?.data?.message || '创建支付宝支付页面失败')
    } finally { paying.value = false }
    return
  }

  try {

    await ElMessageBox.confirm(
        `确认模拟支付 ¥${order.value.totalAmount} 吗？`,
        '支付确认',
        {
          confirmButtonText: '是',
          cancelButtonText: '否'
        }
    )

    await mockPayBooking(
        order.value.orderId
    )

    ElMessage.success('支付成功')

    await loadOrder()

  } catch (error) {

    if (error === 'cancel') {
      return
    }

    ElMessage.error(
        error.response?.data?.message ||
        '支付失败'
    )
  }
}

const handleQueryPayment = async () => {
  if (!order.value || queryingPayment.value) return
  queryingPayment.value = true
  try {
    const response = await queryAlipayPayment(order.value.orderId)
    const status = response.data.data
    if (status === 'PAID') {
      ElMessage.success('支付宝付款已确认')
      await loadOrder()
    } else if (status === 'NEEDS_REVIEW') {
      ElMessage.warning('渠道已收款，但订单状态已变化，管理员将核对并处理退款')
      await loadOrder()
    } else if (status === 'REFUNDED') {
      ElMessage.success('渠道已确认退款')
      await loadOrder()
    } else if (status === 'CLOSED') {
      ElMessage.warning('支付宝交易已关闭，请重新发起支付')
    } else {
      ElMessage.info('尚未查到付款成功，请完成沙箱支付后重试')
    }
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '查询支付宝支付结果失败')
  } finally {
    queryingPayment.value = false
  }
}

const formatDate = (date)=>{

  if(!date){
    return null
  }


  const y = date.getFullYear()

  const m =
      String(date.getMonth()+1)
          .padStart(2,'0')


  const d =
      String(date.getDate())
          .padStart(2,'0')


  return `${y}-${m}-${d}`

}

const getIdTypeText = (type) => {

  const map = {
    ID_CARD: '身份证',
    PASSPORT: '护照',
    OTHER: '其他证件'
  }

  return map[type] || type || '-'
}


const maskIdNumber = (idNumber) => {

  if (!idNumber) {
    return '-'
  }

  if (idNumber.length <= 8) {
    return idNumber
  }

  return (
      idNumber.slice(0, 4) +
      '********' +
      idNumber.slice(-4)
  )
}

onMounted(() => {
  loadOrder()
})

const openRefundDialog = ()=>{

  refundReason.value=''

  refundDialogVisible.value=true

}



const submitRefund = async()=>{

  if (refunding.value || !refundReason.value.trim()) return

  try {

    refunding.value = true


    await request.post(
        '/v1/customer/refund/apply',
        null,
        {
          params:{
            orderId:order.value.orderId,
            reason:refundReason.value.trim()
          }
        }
    )


    ElMessage.success(
        "退款申请成功"
    )


    refundDialogVisible.value=false

    await loadOrder()


  } catch(error){


    ElMessage.error(
        error.response?.data?.message
        || "退款申请失败"
    )


  } finally {
    refunding.value = false
  }

}

onMounted(loadPaymentOptions)
</script>


<style scoped>

.detail-page {
  min-height: 100vh;
  padding: 50px 20px;
  background: #f5f7fa;
}

.detail-container {
  max-width: 1000px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 25px;
  margin-bottom: 25px;
}

.page-header h1 {
  margin: 0;
}

.price {
  font-size: 22px;
  color: #d97706;
}

.actions {
  margin-top: 30px;

  display: flex;
  justify-content: flex-end;

  gap: 12px;
}

</style>
