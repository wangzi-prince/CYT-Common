<script setup lang="ts">
import { ref } from 'vue';
export interface ApprovalField {
  key: string;
  id: string;
  label: string;
  control: string;
  required: boolean;
  read_only: boolean;
  hint: string;
  example: unknown;
  options: { key: string; label: string }[];
  config: Record<string, Record<string, unknown>>;
}
const props = defineProps<{
  fields: ApprovalField[];
  modelValue: Record<string, unknown>;
  disabled?: boolean;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>];
  invalid: [invalid: boolean];
  upload: [key: string, file: File];
}>();
const drafts = ref<Record<string, string>>({});
const errors = ref<Record<string, boolean>>({});
function change(key: string, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value });
}
function jsonChange(key: string, value: string) {
  drafts.value[key] = value;
  try {
    change(key, value.trim() ? JSON.parse(value) : undefined);
    errors.value[key] = false;
  } catch {
    errors.value[key] = true;
  }
  emit('invalid', Object.values(errors.value).some(Boolean));
}
function jsonValue(field: ApprovalField) {
  return (
    drafts.value[field.key] ??
    (props.modelValue[field.key] === undefined
      ? ''
      : JSON.stringify(props.modelValue[field.key], null, 2))
  );
}
function dateType(field: ApprovalField) {
  return field.config?.date?.type === 'hour' ? 'datetime' : 'date';
}
</script>
<template>
  <el-form label-position="top" class="approval-fields" :disabled="disabled">
    <el-form-item
      v-for="field in fields"
      :key="field.key"
      :label="field.label || field.key"
      :required="field.required && !field.read_only"
      :error="errors[field.key] ? '请填写合法的 JSON' : undefined"
    >
      <p v-if="field.read_only" class="form-hint">由企微自动计算或展示</p>
      <el-input
        v-else-if="['Text', 'Textarea', 'Money', 'Number'].includes(field.control)"
        :model-value="String(modelValue[field.key] ?? '')"
        :type="field.control === 'Textarea' ? 'textarea' : 'text'"
        :rows="3"
        :placeholder="field.hint || (field.control === 'Money' ? '金额，如 123.45' : '请输入')"
        :inputmode="['Money', 'Number'].includes(field.control) ? 'decimal' : 'text'"
        @update:model-value="change(field.key, $event)"
      />
      <el-date-picker
        v-else-if="field.control === 'Date'"
        :model-value="modelValue[field.key] as string"
        :type="dateType(field)"
        :value-format="dateType(field) === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'"
        placeholder="北京时间"
        @update:model-value="change(field.key, $event)"
      />
      <el-select
        v-else-if="field.control === 'Selector'"
        :model-value="modelValue[field.key] as string | string[]"
        :multiple="field.config?.selector?.type === 'multi'"
        clearable
        filterable
        placeholder="请选择"
        @update:model-value="change(field.key, $event)"
      >
        <el-option
          v-for="option in field.options"
          :key="option.key"
          :label="option.label"
          :value="option.key"
        />
      </el-select>
      <template v-else-if="['Contact', 'File', 'RelatedApproval'].includes(field.control)">
        <el-select
          :model-value="(modelValue[field.key] || []) as string[]"
          multiple
          filterable
          allow-create
          default-first-option
          clearable
          :placeholder="
            field.control === 'Contact'
              ? '输入成员 userid / 部门 ID，回车添加'
              : field.control === 'File'
                ? '输入附件 file_id，回车添加'
                : '输入关联审批单号，回车添加'
          "
          @update:model-value="change(field.key, $event)"
        />
        <el-upload
          v-if="field.control === 'File'"
          :auto-upload="false"
          :show-file-list="false"
          :on-change="(file: { raw?: File }) => file.raw && emit('upload', field.key, file.raw)"
          ><el-button size="small" style="margin-top: 8px">上传附件（≤10MB）</el-button></el-upload
        >
      </template>
      <template v-else>
        <el-input
          :model-value="jsonValue(field)"
          type="textarea"
          :rows="5"
          spellcheck="false"
          :placeholder="JSON.stringify(field.example, null, 2)"
          @update:model-value="jsonChange(field.key, $event)"
        />
        <p class="form-hint">
          {{
            field.control === 'Table'
              ? '按行填写 JSON 数组；每行使用业务字段名或控件 ID。'
              : '填写业务 JSON，可按下方示例调整。'
          }}
        </p>
        <el-button
          link
          type="primary"
          size="small"
          @click="jsonChange(field.key, JSON.stringify(field.example, null, 2))"
          >填入结构示例</el-button
        >
      </template>
      <p v-if="!field.read_only" class="approval-field-key mono">{{ field.key }}</p>
    </el-form-item>
  </el-form>
</template>
<style scoped>
.approval-fields :deep(.el-select),
.approval-fields :deep(.el-date-editor) {
  width: 100%;
}
.approval-field-key {
  width: 100%;
  margin: 5px 0 0;
  color: var(--app-text-muted);
  font-size: 11px;
}
</style>
