import type { Operation, Schema } from '../api/types';
const field = (type: string, description: string): Schema => ({ type, description });
const payload: Schema = {
  type: 'object',
  description: 'OA 花名册业务结果（设计示例）',
  properties: {
    employees: {
      type: 'array',
      description: '本页成员及其组织、直属领导信息',
      items: {
        type: 'object',
        properties: {
          employee_id: field('string', 'OA 成员唯一 ID；可映射企微 userid'),
          name: field('string', '成员姓名'),
          department_id: field('string', '主部门 ID'),
          department_name: field('string', '主部门名称'),
          direct_manager: {
            type: ['object', 'null'],
            description: '经过明确规则确认的直属领导；无法唯一确认时返回 null，不自动猜测',
            properties: {
              employee_id: field('string', '直属领导成员 ID'),
              name: field('string', '直属领导姓名'),
            },
          },
          manager_source: {
            ...field('string', '领导关系来源；部门负责人不能无条件等同于直属领导'),
            enum: ['explicit_assignment', 'department_leader', 'unresolved'],
          },
        },
      },
    },
    next_cursor: { ...field('string', '下一页游标；null 表示已结束'), type: ['string', 'null'] },
    warnings: {
      type: 'array',
      description: '领导未配置、存在多个候选或数据异常等可处理的问题',
      items: field('string', '异常说明'),
    },
  },
};
export const oaRosterExample: Operation = {
  key: 'oa.organization.roster',
  platform: 'oa',
  integration_kind: 'internal',
  path: '/integrations/oa/organization/roster',
  summary: '查询含直属领导的组织花名册',
  description:
    '设计示例：OA 整理企微通讯录、组织部门和已确认的领导关系，向其他系统开放统一花名册。未注册、未连接、不可执行。',
  category: '组织架构',
  quota: '60/min',
  sensitive: false,
  idempotent: false,
  timeout_s: 30,
  tags: ['read'],
  sort_order: 0,
  schema: {
    type: 'object',
    properties: {
      department_id: field('string', '查询部门 ID；不填时使用调用方获准的组织范围'),
      include_children: { ...field('boolean', '是否包含下级部门成员'), default: true },
      cursor: field('string', '上一页返回的 next_cursor；首次请求不填'),
      page_size: { ...field('integer', '每页成员数量，建议 1–200'), default: 50 },
    },
  },
  response_schema: {
    type: 'object',
    description: '假设通过配置型 HTTP 接口开放，保持现有网关包装',
    properties: {
      status_code: field('integer', 'OA 提供方 HTTP 状态码'),
      data: payload,
      mock: field('boolean', '是否为模拟提供方结果'),
    },
  },
};
