import { lazy, Suspense } from 'react';
import { createBrowserRouter, Link, useRouteError } from 'react-router-dom';
import { WorkspaceLayout } from '../layouts/WorkspaceLayout';
import { Loading } from '../shared/components/Feedback';

const LibrariesPage = lazy(() => import('../pages/LibrariesPage'));
const LibraryWorkspacePage = lazy(() => import('../pages/LibraryWorkspacePage'));
function RouteError() {
  useRouteError();
  return (
    <div className="feedback">
      <h2>页面暂时无法打开</h2>
      <p>请刷新后重试，已保存的研究资料不会受到影响。</p>
      <Link to="/">返回资料库</Link>
    </div>
  );
}
export const router = createBrowserRouter([
  {
    element: <WorkspaceLayout />,
    errorElement: <RouteError />,
    children: [
      {
        path: '/',
        element: (
          <Suspense fallback={<Loading />}>
            <LibrariesPage />
          </Suspense>
        ),
      },
      {
        path: '/libraries/:libraryId',
        element: (
          <Suspense fallback={<Loading />}>
            <LibraryWorkspacePage />
          </Suspense>
        ),
      },
      {
        path: '*',
        element: (
          <div className="feedback">
            <h2>页面不存在</h2>
            <Link to="/">返回资料库</Link>
          </div>
        ),
      },
    ],
  },
]);
