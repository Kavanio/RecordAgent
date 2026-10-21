import React, { useState, useRef, useEffect, useLayoutEffect, useId } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence, LayoutGroup } from 'framer-motion';
import { Check, Settings, Sparkles, Info } from 'lucide-react';
import { useModel } from '../../context/ModelContext';
import { VendorIcon } from './VendorIcon';
import { useMessage } from '../common/Message';
import styles from './ModelSelector.module.css';

interface ModelSelectorProps {
  onOpenSettings: () => void;
  className?: string;
}

const EASE = [0.2, 0, 0, 1] as const;
const SPRING_SOFT = { type: 'spring' as const, stiffness: 420, damping: 32 };

function usePrefersReducedMotion() {
  const [reduceMotion, setReduceMotion] = useState(false);

  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const sync = () => setReduceMotion(mq.matches);
    sync();
    mq.addEventListener('change', sync);
    return () => mq.removeEventListener('change', sync);
  }, []);

  return reduceMotion;
}

export const ModelSelector: React.FC<ModelSelectorProps> = ({ onOpenSettings, className }) => {
  const {
    activeModelId,
    activeProviderId,
    getActiveModel,
    getActiveProvider,
    getAllModels,
    selectActiveModel,
    temperature,
    maxTokens,
  } = useModel();

  const { showMessage } = useMessage();
  const [isOpen, setIsOpen] = useState(false);
  const [hoveredModelId, setHoveredModelId] = useState<string | null>(null);
  const [pinnedDetailId, setPinnedDetailId] = useState<string | null>(null);
  const [coords, setCoords] = useState<{ bottom: number; left: number } | null>(null);
  const [mounted, setMounted] = useState(false);

  const reduceMotion = usePrefersReducedMotion();
  const layoutGroupId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  const activeModel = getActiveModel();
  const activeProvider = getActiveProvider();
  const allModels = getAllModels();

  useEffect(() => {
    setMounted(true);
  }, []);

  // Update floating popover position
  useLayoutEffect(() => {
    if (!isOpen) return;

    const updatePosition = () => {
      const trigger = triggerRef.current;
      if (!trigger) return;
      const rect = trigger.getBoundingClientRect();
      const popoverBottom = window.innerHeight - rect.top + 8;
      const totalWidth = 480;
      let left = rect.right - totalWidth;
      if (left < 16) {
        left = Math.max(16, rect.left);
      }
      if (left + totalWidth > window.innerWidth - 16) {
        left = Math.max(16, window.innerWidth - totalWidth - 16);
      }

      setCoords({
        bottom: popoverBottom,
        left,
      });
    };

    updatePosition();
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [isOpen]);

  // Click outside to close
  useEffect(() => {
    if (!isOpen) return;

    const handlePointerDown = (e: MouseEvent | TouchEvent) => {
      const target = e.target as Node | null;
      if (!target) return;
      if (
        triggerRef.current?.contains(target) ||
        popoverRef.current?.contains(target)
      ) {
        return;
      }
      setIsOpen(false);
      setPinnedDetailId(null);
      setHoveredModelId(null);
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsOpen(false);
        setPinnedDetailId(null);
        setHoveredModelId(null);
        triggerRef.current?.focus();
      }
    };

    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('touchstart', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('touchstart', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  const handleSelectModel = async (providerId: string, modelId: string) => {
    try {
      await selectActiveModel(providerId, modelId);
      setIsOpen(false);
      setPinnedDetailId(null);
      setHoveredModelId(null);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '切换模型失败');
    }
  };

  // Determine current active preview/detail model for the side panel
  const displayModelId = pinnedDetailId || hoveredModelId || activeModelId;
  const currentPreviewModel = allModels.find(
    (m) => m.id === displayModelId || `${m.providerId}-${m.id}` === displayModelId
  ) || allModels[0];

  const displayModelName = activeModel?.name || '选择模型';
  const displayVendorType = activeProvider?.type || activeProviderId || 'custom';

  return (
    <div className={`${styles.container} ${className || ''}`}>
      {/* Trigger Button */}
      <button
        ref={triggerRef}
        type="button"
        className={`${styles.triggerBtn} ${isOpen ? styles.triggerBtnOpen : ''}`}
        onClick={() => {
          setIsOpen((prev) => !prev);
          if (isOpen) {
            setPinnedDetailId(null);
            setHoveredModelId(null);
          }
        }}
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        title="点击切换模型"
      >
        <div className={styles.triggerModelInfo}>
          <span className={styles.triggerVendorIcon}>
            <VendorIcon vendor={displayVendorType} size={14} />
          </span>
          <span className={styles.triggerModelName}>{displayModelName}</span>
          {activeModel?.supportsReasoning && (
            <span className={styles.triggerBadge}>R1</span>
          )}
        </div>

      </button>

      {/* Floating Popover via Portal */}
      {mounted &&
        createPortal(
          <AnimatePresence>
            {isOpen && coords && (
              <motion.div
                ref={popoverRef}
                className={styles.popoverContainer}
                initial={
                  reduceMotion
                    ? { opacity: 0 }
                    : { opacity: 0, y: 8, scale: 0.96, filter: 'blur(4px)' }
                }
                animate={
                  reduceMotion
                    ? { opacity: 1 }
                    : { opacity: 1, y: 0, scale: 1, filter: 'blur(0px)' }
                }
                exit={
                  reduceMotion
                    ? { opacity: 0 }
                    : { opacity: 0, y: 6, scale: 0.98, filter: 'blur(2px)' }
                }
                transition={{ duration: 0.18, ease: EASE }}
                style={{
                  bottom: coords.bottom,
                  left: coords.left,
                }}
              >
                {/* Left Panel: Model List */}
                <div className={`${styles.panelCard} ${styles.listPanel}`}>
                  <div className={styles.listHeader}>
                    <span>可用模型</span>
                    <span style={{ fontSize: '10px' }}>{allModels.length} 个</span>
                  </div>

                  <LayoutGroup id={layoutGroupId}>
                    <div
                      className={styles.listScrollArea}
                      onMouseLeave={() => {
                        if (!pinnedDetailId) setHoveredModelId(null);
                      }}
                    >
                      {allModels.length === 0 ? (
                        <div className={styles.emptyTip}>
                          暂无配置模型
                        </div>
                      ) : (
                        allModels.map((model) => {
                          const isSelected = model.id === activeModelId;
                          const isHovered = hoveredModelId === model.id;
                          const isPinned = pinnedDetailId === model.id;

                          return (
                            <div
                              key={`${model.providerId}-${model.id}`}
                              className={`${styles.modelItem} ${
                                isSelected ? styles.modelItemActive : ''
                              }`}
                              onMouseEnter={() => setHoveredModelId(model.id)}
                            >
                              {/* Smooth Hover/Active Highlight */}
                              {(isSelected || isHovered) && (
                                <motion.div
                                  layoutId={`${layoutGroupId}-highlight`}
                                  className={styles.modelItemHighlightBg}
                                  transition={SPRING_SOFT}
                                />
                              )}

                              <button
                                type="button"
                                className={styles.modelItemContent}
                                onClick={() => handleSelectModel(model.providerId, model.id)}
                              >
                                <span className={styles.itemIconWrapper}>
                                  <VendorIcon
                                    vendor={model.providerType || model.providerId}
                                    size={14}
                                  />
                                </span>

                                <div className={styles.itemTextGroup}>
                                  <span className={styles.itemName}>{model.name}</span>
                                  {model.supportsReasoning && (
                                    <span
                                      className={`${styles.itemTag} ${styles.itemTagReasoning}`}
                                    >
                                      推理
                                    </span>
                                  )}
                                </div>

                                {isSelected && (
                                  <span className={styles.itemCheckIcon}>
                                    <Check size={14} />
                                  </span>
                                )}
                              </button>

                              {/* Detail/Pin Button */}
                              <button
                                type="button"
                                className={`${styles.itemEditBtn} ${
                                  isPinned ? styles.itemEditBtnActive : ''
                                }`}
                                title="查看模型详情"
                                aria-label={`查看 ${model.name} 详情`}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setPinnedDetailId((prev) =>
                                    prev === model.id ? null : model.id
                                  );
                                }}
                              >
                                <Info size={13} />
                              </button>
                            </div>
                          );
                        })
                      )}
                    </div>
                  </LayoutGroup>

                  <div className={styles.listDivider} />

                  <button
                    type="button"
                    className={styles.listFooterBtn}
                    onClick={() => {
                      setIsOpen(false);
                      onOpenSettings();
                    }}
                  >
                    <Settings size={13} />
                    <span>管理模型与 API Key...</span>
                  </button>
                </div>

                {/* Right Panel: Flyout Detail Preview */}
                {currentPreviewModel && (
                  <motion.div
                    key="side-detail-panel"
                    className={`${styles.panelCard} ${styles.sidePanel}`}
                    initial={
                      reduceMotion
                        ? { opacity: 0 }
                        : { opacity: 0, x: -6, scale: 0.98, filter: 'blur(3px)' }
                    }
                    animate={
                      reduceMotion
                        ? { opacity: 1 }
                        : { opacity: 1, x: 0, scale: 1, filter: 'blur(0px)' }
                    }
                    exit={
                      reduceMotion
                        ? { opacity: 0 }
                        : { opacity: 0, x: -4, scale: 0.98 }
                    }
                    transition={{ duration: 0.16, ease: EASE }}
                  >
                    <div className={styles.sideHeader}>
                      <div className={styles.itemIconWrapper}>
                        <VendorIcon
                          vendor={
                            currentPreviewModel.providerType ||
                            currentPreviewModel.providerId
                          }
                          size={16}
                        />
                      </div>
                      <div className={styles.sideTitleGroup}>
                        <span className={styles.sideModelName}>
                          {currentPreviewModel.name}
                        </span>
                        <span className={styles.sideProviderName}>
                          {currentPreviewModel.providerName}
                        </span>
                      </div>
                    </div>

                    {currentPreviewModel.description ? (
                      <p className={styles.sideDescription}>
                        {currentPreviewModel.description}
                      </p>
                    ) : (
                      <p className={styles.sideDescription}>
                        由 {currentPreviewModel.providerName} 提供的智能大语言模型，支持对话、编程与代码辅助。
                      </p>
                    )}

                    <div className={styles.sideSection}>
                      <span className={styles.sideSectionTitle}>能力特性</span>
                      <div className={styles.sideTagList}>
                        {currentPreviewModel.supportsReasoning ? (
                          <span className={`${styles.sideTag} ${styles.sideTagActive}`}>
                            <Sparkles size={11} />
                            深度推理 (R1)
                          </span>
                        ) : (
                          <span className={styles.sideTag}>标准会话</span>
                        )}
                        <span className={styles.sideTag}>流式响应</span>
                        <span className={styles.sideTag}>代码编写</span>
                      </div>
                    </div>

                    <div className={styles.sideSection}>
                      <span className={styles.sideSectionTitle}>运行参数</span>
                      <div className={styles.sideParamRow}>
                        <span>温度 (Temperature)</span>
                        <span className={styles.sideParamValue}>{temperature}</span>
                      </div>
                      <div className={styles.sideParamRow}>
                        <span>最大 Token (Max Tokens)</span>
                        <span className={styles.sideParamValue}>{maxTokens}</span>
                      </div>
                      {currentPreviewModel.contextWindow && (
                        <div className={styles.sideParamRow}>
                          <span>上下文窗口</span>
                          <span className={styles.sideParamValue}>
                            {currentPreviewModel.contextWindow >= 1000000
                              ? `${(currentPreviewModel.contextWindow / 1000000).toFixed(0)}M`
                              : `${Math.round(currentPreviewModel.contextWindow / 1000)}K`}
                          </span>
                        </div>
                      )}
                    </div>
                  </motion.div>
                )}
              </motion.div>
            )}
          </AnimatePresence>,
          document.body
        )}
    </div>
  );
};

export default ModelSelector;
