import {
  ArrowRightOutlined,
  FileTextOutlined,
  FolderOutlined,
  MessageOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Input } from 'antd';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { LibraryDialog } from '../features/libraries/components/LibraryDialog';
import { librariesQuery } from '../features/libraries/queries';
import { ErrorState, Loading } from '../shared/components/Feedback';
import { dateLabel } from '../shared/utils/format';
import styles from './LibrariesPage.module.css';
export default function LibrariesPage() {
  const libraries = useQuery(librariesQuery);
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();
  const filtered = libraries.data?.filter((l) =>
    `${l.name} ${l.description || ''}`.toLowerCase().includes(search.toLowerCase()),
  );
  return (
    <div className={styles.page}>
      <header className={styles.topbar}>
        <span>你的研究空间</span>
        <span className={styles.quiet}>收集 · 理解 · 求证</span>
      </header>
      <section className={styles.hero}>
        <div className={styles.eyebrow}>A LITTLE CLARITY. A NEW DISCOVERY.</div>
        <h1>
          让资料相聚。
          <br />
          <span>让想法向前。</span>
        </h1>
        <p>
          从一篇论文到一次深入研究。将文献、笔记和复现资料整理在一起，
          <br className={styles.desktopBreak} />
          在有据可依的对话中，发现下一个答案。
        </p>
        <Button
          aria-label="创建资料库"
          size="large"
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setCreating(true)}
        >
          创建资料库
        </Button>
        <div className={styles.heroArt} aria-hidden="true">
          <div className={styles.orbit} />
          <div className={styles.paperBack} />
          <div className={styles.paper}>
            <div className={styles.paperTag}>RESEARCH NOTES</div>
            <div className={styles.paperTitle}>
              从阅读
              <br />
              到理解。
            </div>
            <div className={styles.paperLine} />
            <div className={styles.paperLine} />
            <div className={styles.paperLineShort} />
            <span className={styles.paperNumber}>01 / A NEW PERSPECTIVE</span>
          </div>
          <div className={styles.evidence}>
            <SafetyCertificateOutlined />
            <span>每个发现，都有出处</span>
          </div>
        </div>
      </section>
      <section className={styles.collection}>
        <div className={styles.collectionHeading}>
          <h2>
            我的资料库 <span>{libraries.data?.length ?? '—'}</span>
          </h2>
          <Input
            aria-label="搜索资料库"
            placeholder="搜索资料库"
            prefix={<SearchOutlined />}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            allowClear
            className={styles.search}
          />
        </div>
        {libraries.isPending ? (
          <Loading />
        ) : libraries.isError ? (
          <ErrorState error={libraries.error} retry={() => libraries.refetch()} />
        ) : (
          <div className={styles.grid}>
            {filtered?.map((l, i) => (
              <Link to={`/libraries/${l.id}`} className={styles.card} key={l.id}>
                <div className={styles.cardTop}>
                  <span className={`${styles.folder} ${styles[`tone${i % 3}`]}`}>
                    <FolderOutlined />
                  </span>
                  <ArrowRightOutlined />
                </div>
                <h3>{l.name}</h3>
                <p>{l.description || '一处专属空间，连接资料与想法。'}</p>
                <footer>
                  <span>更新于 {dateLabel(l.updatedAt)}</span>
                  <span>进入资料库 →</span>
                </footer>
              </Link>
            ))}
            {!search && (
              <button type="button" className={styles.createCard} onClick={() => setCreating(true)}>
                <span>
                  <PlusOutlined />
                </span>
                <strong>开启一个新主题</strong>
                <small>论文、Markdown、配置文件，均可收集</small>
              </button>
            )}
            {search && !filtered?.length && <p className="muted">没有找到匹配的资料库。</p>}
          </div>
        )}
      </section>
      <div className={styles.principles}>
        <span>
          <FileTextOutlined /> 多种资料，一个空间
        </span>
        <span>
          <MessageOutlined /> 连续追问，深入理解
        </span>
        <span>
          <SafetyCertificateOutlined /> 回到原文，核验答案
        </span>
      </div>
      {creating && (
        <LibraryDialog onClose={() => setCreating(false)} onCreated={(l) => navigate(`/libraries/${l.id}`)} />
      )}
    </div>
  );
}
