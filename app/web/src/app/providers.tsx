import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import { ConversationStreamProvider } from '../features/conversations/hooks/useConversationStream';
import { ApiError } from '../shared/api/http';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      retry: (count, error) =>
        count < 1 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
    },
    mutations: { retry: false },
  },
});
export function Providers({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider
      locale={zhCN}
      button={{ autoInsertSpace: false }}
      theme={{
        token: {
          colorPrimary: '#0071e3',
          colorInfo: '#0071e3',
          colorText: '#1d1d1f',
          colorTextSecondary: '#6e6e73',
          colorBgLayout: '#f5f5f7',
          colorBorder: '#dedee3',
          borderRadius: 10,
          fontFamily:
            '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
          controlHeight: 38,
        },
        components: {
          Button: { primaryShadow: 'none' },
          Modal: { borderRadiusLG: 20 },
          Drawer: { paddingLG: 24 },
          Table: { headerBg: '#fafafa', rowHoverBg: '#f5f8fc' },
        },
      }}
    >
      <App>
        <QueryClientProvider client={queryClient}>
          <ConversationStreamProvider>{children}</ConversationStreamProvider>
        </QueryClientProvider>
      </App>
    </ConfigProvider>
  );
}
