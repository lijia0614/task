import request from './request'

export const taskComments = (taskId) => request.get(`/tasks/${taskId}/comments`)
export const addTaskComment = (taskId, content) => request.post(`/tasks/${taskId}/comments`, { content })
export const reportComments = (reportId) => request.get(`/reports/${reportId}/comments`)
export const addReportComment = (reportId, content) => request.post(`/reports/${reportId}/comments`, { content })
export const replyComment = (id, content) => request.post(`/comments/${id}/reply`, { content })
