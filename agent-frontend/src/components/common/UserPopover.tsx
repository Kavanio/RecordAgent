import React from 'react';
import { Settings as SettingsIcon, LogOut } from 'lucide-react';
import { useMessage } from './Message';
import styles from './UserPopover.module.css';

export interface UserPopoverProps {
  isOpen: boolean;
  onClose: () => void;
  onOpenSettings: () => void;
}

export const UserPopover: React.FC<UserPopoverProps> = ({
  isOpen,
  onClose,
  onOpenSettings,
}) => {
  const { showMessage } = useMessage();
  if (!isOpen) return null;

  const handleResetSession = () => {
    onClose();
    showMessage('success', '已重置会话');
  };

  return (
    <>
      <div className={styles.popoverOverlay} onClick={onClose} />
      <div className={styles.popoverCard}>
        {/* Header Profile Info */}
        <div className={styles.header}>
          <div className={styles.avatar}>BA</div>
          <div className={styles.userInfo}>
            <span className={styles.userName}>ButvanAgent</span>
            <span className={styles.userPlan}>免费社区版</span>
          </div>
        </div>

        {/* Menu Items matching Screenshot 2 */}
        <div className={styles.menuList}>
          <button className={styles.menuItem} onClick={() => { onClose(); onOpenSettings(); }}>
            <div className={styles.menuItemLeft}>
              <SettingsIcon size={14} />
              <span>设置</span>
            </div>
            <span className={styles.shortcutKey}>⌘,</span>
          </button>

          <button className={`${styles.menuItem} ${styles.dangerItem}`} onClick={handleResetSession}>
            <div className={styles.menuItemLeft}>
              <LogOut size={14} />
              <span>重置状态</span>
            </div>
          </button>
        </div>
      </div>
    </>
  );
};
