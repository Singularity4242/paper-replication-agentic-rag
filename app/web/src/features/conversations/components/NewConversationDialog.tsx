import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, App, Checkbox, Form, Input, Modal } from 'antd';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/http';
import type { ResearchDocument } from '../../documents/types';
import { conversationsApi } from '../api';
import { conversationsKey } from '../queries';
import type { Conversation } from '../types';
export function NewConversationDialog({
  libraryId,
  documents,
  onClose,
  onCreated,
}: {
  libraryId: number;
  documents: ResearchDocument[];
  onClose: () => void;
  onCreated: (value: Conversation) => void;
}) {
  const available = documents.filter((d) => d.indexStatus === 'INDEXED');
  const [selected, setSelected] = useState(available.slice(0, 500).map((d) => d.id));
  const [form] = Form.useForm();
  const { message } = App.useApp();
  const client = useQueryClient();
  const create = useMutation({
    mutationFn: (title: string) => conversationsApi.create(libraryId, title.trim(), selected),
    onSuccess: async (value) => {
      await client.invalidateQueries({ queryKey: conversationsKey(libraryId) });
      onCreated(value);
      onClose();
    },
    onError: (e) => message.error(errorMessage(e)),
  });
  return (
    <Modal
      open
      title="开始一段研究对话"
      onCancel={onClose}
      okText="创建会话"
      cancelText="取消"
      confirmLoading={create.isPending}
      okButtonProps={{ disabled: selected.length === 0 || selected.length > 500 }}
      onOk={() => form.submit()}
    >
      <p className="muted">选择本次讨论的资料。创建后范围固定，需要使用新资料时请新建会话。</p>
      <Form
        layout="vertical"
        form={form}
        initialValues={{ title: '新的研究对话' }}
        onFinish={(v) => create.mutate(v.title)}
      >
        <Form.Item
          name="title"
          label="会话名称"
          rules={[{ required: true, whitespace: true, message: '请输入会话名称' }, { max: 200 }]}
        >
          <Input maxLength={200} autoFocus />
        </Form.Item>
      </Form>
      <div className="scope-heading">
        <strong>问答资料 · {selected.length} 份</strong>
        <Checkbox
          checked={available.length > 0 && selected.length === available.length}
          indeterminate={selected.length > 0 && selected.length < available.length}
          onChange={(e) => setSelected(e.target.checked ? available.slice(0, 500).map((d) => d.id) : [])}
        >
          全选（最多 500 份）
        </Checkbox>
      </div>
      {available.length ? (
        <Checkbox.Group
          className="scope-options"
          value={selected}
          onChange={(v) => setSelected(v as number[])}
        >
          {available.map((d) => (
            <Checkbox key={d.id} value={d.id}>
              {d.originalFilename}
            </Checkbox>
          ))}
        </Checkbox.Group>
      ) : (
        <Alert
          type="info"
          title="还没有可用于问答的资料"
          description="请先上传文件，等待至少一份资料解析完成。"
          showIcon
        />
      )}
      {documents.some((d) => d.indexStatus !== 'INDEXED') && (
        <p className="small muted">正在解析、解析失败及不支持解析的文件暂不可选。</p>
      )}
    </Modal>
  );
}
