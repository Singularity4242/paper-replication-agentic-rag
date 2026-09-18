import { Alert, Button, Spin } from 'antd';
import { errorMessage } from '../api/http';
export function Loading({ label = '正在加载' }: { label?: string }) {
  return (
    <div className="feedback" role="status">
      <Spin />
      <span>{label}</span>
    </div>
  );
}
export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <div className="feedback">
      <Alert
        title="暂时无法加载"
        description={errorMessage(error)}
        type="error"
        showIcon
        action={retry && <Button onClick={retry}>重试</Button>}
      />
    </div>
  );
}
