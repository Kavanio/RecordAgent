import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { FileCode, FileCog, FileJson, FileText, FolderTree, Image, RefreshCw } from 'lucide-react';
import type { FileTreeNode } from '../../types/team';
import { fetchProjectFileTree } from '../../services/fileTreeApi';
import { LoadingTree } from '../common/LoadingTree';
import {
  TreeExpander,
  TreeIcon,
  TreeLabel,
  TreeNode,
  TreeNodeContent,
  TreeNodeTrigger,
  TreeProvider,
  TreeView,
} from '../ui/tree';
import styles from './ProjectFileTree.module.css';

interface ProjectFileTreeProps {
  projectId: string;
  projectPath: string;
}

interface TreeListProps {
  nodes: FileTreeNode[];
  depth: number;
  parentPath?: boolean[];
}

function fileIcon(name: string): React.ReactNode {
  const extension = name.split('.').pop()?.toLowerCase();
  if (['ts', 'tsx', 'js', 'jsx', 'java', 'rs', 'py', 'go', 'kt'].includes(extension ?? '')) {
    return <FileCode size={15} />;
  }
  if (['json', 'jsonl'].includes(extension ?? '')) return <FileJson size={15} />;
  if (['md', 'mdx', 'txt'].includes(extension ?? '')) return <FileText size={15} />;
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico'].includes(extension ?? '')) {
    return <Image size={15} />;
  }
  if (['yml', 'yaml', 'toml', 'xml', 'properties'].includes(extension ?? '')) {
    return <FileCog size={15} />;
  }
  return undefined;
}

function countNodes(nodes: FileTreeNode[]): number {
  return nodes.reduce((count, node) => count + 1 + countNodes(node.children ?? []), 0);
}

function TreeList({ nodes, depth, parentPath = [] }: TreeListProps) {
  return (
    <>
      {nodes.map((node, index) => {
        const isDir = node.type === 'dir';
        const children = node.children ?? [];
        const hasChildren = isDir && children.length > 0;
        const isLast = index === nodes.length - 1;

        return (
          <TreeNode
            key={node.path}
            nodeId={node.path}
            level={depth}
            isLast={isLast}
            parentPath={parentPath}
            hasChildren={hasChildren}
          >
            <TreeNodeTrigger title={node.path} aria-label={`${isDir ? '目录' : '文件'} ${node.name}`}>
              <TreeExpander />
              <TreeIcon kind={isDir ? 'folder' : 'file'} icon={isDir ? undefined : fileIcon(node.name)} />
              <TreeLabel>{node.name}</TreeLabel>
            </TreeNodeTrigger>
            <TreeNodeContent>
              <TreeList
                nodes={children}
                depth={depth + 1}
                parentPath={[...parentPath, isLast]}
              />
            </TreeNodeContent>
          </TreeNode>
        );
      })}
    </>
  );
}

/** 项目文件树：只读展示项目目录结构，支持目录展开/收起与手动刷新。 */
export const ProjectFileTree: React.FC<ProjectFileTreeProps> = ({ projectId, projectPath }) => {
  const [tree, setTree] = useState<FileTreeNode[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [treeRevision, setTreeRevision] = useState(0);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const nodes = await fetchProjectFileTree(projectId);
      setTree(nodes);
      setTreeRevision((revision) => revision + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取项目文件失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  const projectName = useMemo(
    () => projectPath.split(/[\\/]/).filter(Boolean).pop() || '项目',
    [projectPath],
  );
  const nodeCount = useMemo(() => countNodes(tree), [tree]);

  return (
    <section className={styles.container} aria-label="项目文件树">
      <div className={styles.header}>
        <span className={styles.projectIcon} aria-hidden="true"><FolderTree size={15} /></span>
        <div className={styles.headerText}>
          <div className={styles.title}>{projectName}</div>
          <p className={styles.path} title={projectPath}>{projectPath}</p>
        </div>
        <button
          type="button"
          className={styles.refresh}
          onClick={() => void load()}
          disabled={isLoading}
          aria-label="刷新项目文件树"
          title="刷新"
        >
          <RefreshCw size={14} className={isLoading ? styles.refreshing : ''} aria-hidden="true" />
        </button>
      </div>

      {tree.length > 0 && !error && (
        <div className={styles.summary} aria-live="polite">
          <span>项目文件</span>
          <span>{nodeCount} 个条目</span>
        </div>
      )}

      {error ? (
        <div className={styles.error} role="status">
          <p>{error}</p>
          <button type="button" className={styles.retry} onClick={() => void load()}>
            重试
          </button>
        </div>
      ) : isLoading && tree.length === 0 ? (
        <div className={styles.loadingState}><LoadingTree size="small" label="正在读取项目文件…" /></div>
      ) : tree.length === 0 ? (
        <p className={styles.empty}>项目目录为空或暂无可展示文件。</p>
      ) : (
        <div className={styles.tree}>
          <TreeProvider
            key={`${projectId}-${treeRevision}`}
            defaultExpandedIds={tree.filter((node) => node.type === 'dir').map((node) => node.path)}
            indent={18}
          >
            <TreeView aria-label={`${projectName} 文件树`}>
              <TreeList nodes={tree} depth={0} />
            </TreeView>
          </TreeProvider>
        </div>
      )}
    </section>
  );
};
