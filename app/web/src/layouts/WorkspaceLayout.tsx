import {
  ArrowLeftOutlined,
  BookOutlined,
  CloseOutlined,
  FolderOutlined,
  MenuOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Drawer } from 'antd';
import { useState } from 'react';
import { NavLink, Outlet, useNavigate, useParams } from 'react-router-dom';
import { LibraryDialog } from '../features/libraries/components/LibraryDialog';
import { librariesQuery } from '../features/libraries/queries';
import styles from './WorkspaceLayout.module.css';
export function WorkspaceLayout() {
  const { libraryId } = useParams();
  const libraries = useQuery(librariesQuery);
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [mobile, setMobile] = useState(false);
  const sidebar = (
    <>
      <NavLink to="/" className={styles.brand} onClick={() => setMobile(false)}>
        <span className={styles.brandIcon}>
          <BookOutlined />
        </span>
        <span>
          研阅<small>RESEARCH WORKSPACE</small>
        </span>
      </NavLink>
      <NavLink
        to="/"
        end
        className={({ isActive }) => `${styles.home} ${isActive ? styles.selected : ''}`}
        onClick={() => setMobile(false)}
      >
        <ArrowLeftOutlined /> 全部资料库
      </NavLink>
      <div className={styles.sectionTitle}>
        <span>我的资料库</span>
        <Button
          type="text"
          size="small"
          aria-label="创建资料库"
          icon={<PlusOutlined />}
          onClick={() => setCreating(true)}
        />
      </div>
      <nav className={styles.libraries} aria-label="资料库导航">
        {libraries.data?.map((l) => (
          <NavLink
            title={l.name}
            key={l.id}
            to={`/libraries/${l.id}`}
            className={`${styles.library} ${String(l.id) === libraryId ? styles.selected : ''}`}
            onClick={() => setMobile(false)}
          >
            <FolderOutlined />
            <span>{l.name}</span>
          </NavLink>
        ))}
        {libraries.data?.length === 0 && (
          <p className={styles.hint}>
            创建第一个资料库，
            <br />
            开始你的研究。
          </p>
        )}
      </nav>
      <div className={styles.footer}>
        <span className={styles.footerMark}>研</span>
        <div>
          让每一个答案，有据可依。<small>论文 · 笔记 · 复现资料</small>
        </div>
      </div>
    </>
  );
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>{sidebar}</aside>
      <div className={styles.mobileBar}>
        <Button aria-label="打开导航" type="text" icon={<MenuOutlined />} onClick={() => setMobile(true)} />
        <span>研阅 · 研究工作台</span>
      </div>
      <Drawer
        title="研究空间"
        placement="left"
        open={mobile}
        onClose={() => setMobile(false)}
        closeIcon={<CloseOutlined />}
      >
        {sidebar}
      </Drawer>
      <main className={styles.main}>
        <Outlet />
      </main>
      {creating && (
        <LibraryDialog
          onClose={() => setCreating(false)}
          onCreated={(l) => {
            setMobile(false);
            navigate(`/libraries/${l.id}`);
          }}
        />
      )}
    </div>
  );
}
