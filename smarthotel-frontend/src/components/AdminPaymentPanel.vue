<template>
  <section>
    <el-button @click="load" :loading="loading">刷新</el-button>
    <el-table :data="records" v-loading="loading" border style="margin-top: 12px">
      <el-table-column prop="paymentNo" label="支付流水号" min-width="210" />
      <el-table-column prop="orderNo" label="订单号" min-width="190" />
      <el-table-column label="方式" width="115"><template #default="{row}">{{ methodText[row.paymentMethod] || row.paymentMethod }}</template></el-table-column>
      <el-table-column label="金额" width="110"><template #default="{ row }">¥{{ row.amount }}</template></el-table-column>
      <el-table-column label="状态" width="105"><template #default="{row}">{{ paymentText[row.paymentStatus] || row.paymentStatus }}</template></el-table-column>
      <el-table-column prop="thirdPartyNo" label="第三方流水" min-width="160" />
      <el-table-column label="支付时间" min-width="170"><template #default="{ row }">{{ row.paidTime ? String(row.paidTime).replace('T', ' ') : '-' }}</template></el-table-column>
      <el-table-column label="渠道核对" width="130"><template #default="{row}"><el-button v-if="row.paymentMethod === 'ALIPAY'" link type="primary" :loading="busy === row.paymentId" @click="reconcile(row.paymentId)">查询支付宝</el-button></template></el-table-column>
    </el-table>
    <AppPagination v-model:current-page="query.page" v-model:page-size="query.pageSize"
                   :total="total" :page-sizes="[10, 20, 50]"
                   style="justify-content: flex-end; margin-top: 15px" @current-change="load" @size-change="load" />
    <h3>支付宝渠道核对与异常收款</h3>
    <p>显示最近100条核对记录，优先展示需处理项目。后台定期核对最近的待支付流水。已关闭订单的异常收款单独处理，不会强行恢复订单或扣减库存。</p>
    <el-table :data="audits" border>
      <el-table-column prop="paymentNo" label="支付流水" min-width="200" />
      <el-table-column label="核对状态" min-width="150"><template #default="{row}">{{ auditText[row.state] || row.state }}</template></el-table-column>
      <el-table-column prop="gatewayStatus" label="渠道状态" min-width="150" />
      <el-table-column prop="receivedAmount" label="渠道金额" width="110" />
      <el-table-column prop="note" label="核对说明" min-width="250" />
      <el-table-column label="操作" width="170"><template #default="{row}"><el-button v-if="['NEEDS_REVIEW','REFUND_REQUESTED'].includes(row.state)" link type="danger" :loading="busy === row.paymentId" @click="refundException(row)">退回异常沙箱收款</el-button></template></el-table-column>
    </el-table>
  </section>
</template>
<script setup>
import AppPagination from './AppPagination.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminPayments, getPaymentAudits, reconcilePayment, refundExceptionPayment } from '../api/admin'
const methodText = {ALIPAY:'支付宝沙箱',MOCK:'开发模拟'}
const paymentText = {PENDING:'待支付',SUCCESS:'已支付',FAILED:'已关闭',REFUNDING:'退款中',REFUNDED:'已退款'}
const auditText = {PENDING:'待付款',MATCHED:'已核对',NEEDS_REVIEW:'异常收款待核对',MISMATCH:'金额或流水不符',ERROR:'渠道查询异常',CLOSED:'交易已关闭',REFUND_REQUESTED:'异常退款处理中',REFUNDED:'已退款'}
const outcomeText = {PAID:'已支付',PENDING:'等待买家付款',NEEDS_REVIEW:'异常收款待核对',REFUNDED:'已退款',CLOSED:'交易已关闭'}
const audits = ref([]), busy = ref(null)
const records = ref([]), total = ref(0), loading = ref(false)
const query = reactive({ page: 1, pageSize: 10 })
const load = async () => {
  loading.value = true
  try {
    const { data } = await getAdminPayments(query)
    if (data.code !== 200) throw new Error(data.message)
    records.value = data.data.records || []; total.value = data.data.total || 0
    audits.value = (await getPaymentAudits()).data.data || []
  } catch (error) { ElMessage.error(error.response?.data?.message || error.message || '支付记录加载失败') }
  finally { loading.value = false }
}
async function reconcile(id){busy.value=id;try{const {data}=await reconcilePayment(id);ElMessage.info('渠道核对结果：'+(outcomeText[data.data] || data.data));await load()}catch(error){ElMessage.error(error.response?.data?.message || '渠道查询失败')}finally{busy.value=null}}
async function refundException(row){
  try{await ElMessageBox.confirm('确认将这笔已关闭订单的异常收款退回支付宝沙箱买家？正常订单请使用退款审核。','异常收款退款',{type:'warning',confirmButtonText:'确认退回',cancelButtonText:'取消'})}catch{return}
  busy.value=row.paymentId;try{await refundExceptionPayment(row.paymentId);ElMessage.success('渠道已确认退款');await load()}catch(error){ElMessage.warning(error.response?.data?.message || '退款结果待确认，后台将按原退款号恢复，请勿重复创建退款')}finally{busy.value=null}
}
onMounted(load)
</script>
