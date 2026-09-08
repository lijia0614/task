import request from './request'

export const reportSummary = (range) => request.get('/admin/reports/summary', { params: { range } })
