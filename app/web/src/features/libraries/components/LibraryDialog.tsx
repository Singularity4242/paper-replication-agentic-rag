import { useMutation, useQueryClient } from '@tanstack/react-query';
import { App, Form, Input, Modal } from 'antd';
import { errorMessage } from '../../../shared/api/http';
import { librariesApi } from '../api';
import type { Library, LibraryInput } from '../types';
export function LibraryDialog({
  library,
  onClose,
  onCreated,
}: {
  library?: Library;
  onClose: () => void;
  onCreated?: (library: Library) => void;
}) {
  const [form] = Form.useForm<LibraryInput>();
  const client = useQueryClient();
  const { message } = App.useApp();
  const mutation = useMutation({
    mutationFn: (value: LibraryInput) =>
      library ? librariesApi.update(library.id, value) : librariesApi.create(value),
    onSuccess: async (result) => {
      await client.invalidateQueries({ queryKey: ['libraries'] });
      message.success(library ? '资料库已更新' : '资料库已创建');
      onClose();
      if (!library) onCreated?.(result);
    },
    onError: (error) => message.error(errorMessage(error)),
  });
  return (
    <Modal
      open
      title={library ? '编辑资料库' : '创建资料库'}
      okText={library ? '保存更改' : '创建资料库'}
      cancelText="取消"
      onCancel={onClose}
      confirmLoading={mutation.isPending}
      onOk={() => form.submit()}
    >
      <p className="muted">为一个研究主题，留一个专属空间。</p>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ name: library?.name || '', description: library?.description || '' }}
        onFinish={(v) => mutation.mutate({ name: v.name.trim(), description: v.description?.trim() || '' })}
      >
        <Form.Item
          name="name"
          label="资料库名称"
          rules={[{ required: true, whitespace: true, message: '请输入资料库名称' }, { max: 128 }]}
        >
          <Input autoFocus placeholder="例如：视觉模型复现研究" maxLength={128} />
        </Form.Item>
        <Form.Item name="description" label="简介" rules={[{ max: 2000 }]}>
          <Input.TextArea rows={3} placeholder="记录研究方向、目标或资料用途（选填）" maxLength={2000} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
