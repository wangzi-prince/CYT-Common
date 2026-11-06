import test from 'node:test';
import assert from 'node:assert/strict';
import {operationDirection,schemaRows,responseEnvelope} from '../src/utils/integration-direction.ts';
test('business tags do not turn a third-party operation into our own exposed capability',()=>{
  assert.equal(operationDirection({platform:'wecom',tags:['business']}),'third_party');
  assert.equal(operationDirection({platform:'oa',moduleKey:'oa'},[{key:'oa',service_kind:'internal'}]),'internal');
  assert.equal(operationDirection({platform:'oa'},[{key:'oa',service_kind:'internal'}]),'third_party');
});
test('nested request and response fields retain array paths, definitions, units and nullable types',()=>{
  const schema={type:'object',$defs:{Manager:{type:'object',properties:{name:{type:['string','null'],description:'直属领导姓名'}}}},properties:{employees:{type:'array',description:'员工列表',items:{type:'object',required:['id'],properties:{id:{type:'string',description:'成员 ID'},manager:{$ref:'#/$defs/Manager'}}}}}};
  const rows=schemaRows(schema);assert.equal(rows.find(r=>r.name==='employees[].id').required,true);assert.equal(rows.find(r=>r.name==='employees[].manager.name').description,'直属领导姓名');assert.equal(rows.find(r=>r.name==='employees[].manager.name').type,'string | null');
  assert.ok(schemaRows(responseEnvelope({response_schema:schema})).some(r=>r.name==='data.employees[].manager.name'));
});
