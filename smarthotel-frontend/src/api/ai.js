import request from './request'

export function recommendRooms(data) {
  return request({ url: '/v1/customer/ai/recommend', method: 'post', data, timeout: 50000 })
}

export function getAIStatus(){return request.get('/v1/customer/ai/status')}
