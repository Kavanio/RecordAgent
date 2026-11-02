import type { CalendarPreferences, ChatTopBarPreferences, StudyWindowMode } from '../../types/preferences';
import { Select } from '../common/Select';
import { LeverSwitch } from '../common/LeverSwitch';
import { SettingsPageLayout } from './SettingsPageLayout';
import { SettingsGroup, SettingsRow } from './SettingsGroup';
import { WritingEffectSettings } from './WritingEffectSettings';

export type FeatureSettingsTab = 'calendar' | 'finance' | 'library' | 'record';

const FEATURE_CONTENT: Record<Exclude<FeatureSettingsTab, 'record'>, {
  title: string;
  description: string;
  detail: string;
}> = {
  calendar: {
    title: '日历',
    description: '管理日程、待办和每日信息的展示方式。',
    detail: '当前日历功能使用默认配置。后续新增的提醒、周起始日和日历同步选项会集中在这里。',
  },
  finance: {
    title: '财务',
    description: '管理账户、收支记录和统计展示偏好。',
    detail: '当前财务功能使用默认配置。后续新增的本位币、预算周期和分类规则会集中在这里。',
  },
  library: {
    title: '资料',
    description: '管理资料库的编辑、分类和阅读偏好。',
    detail: '配置正文打字特效、emoji 图案和本机自定义图片。',
  },
};

interface FeatureSettingsPageProps {
  tab: FeatureSettingsTab;
  studyWindowMode: StudyWindowMode;
  onStudyWindowModeChange: (mode: StudyWindowMode) => void;
  chatTopBar: ChatTopBarPreferences;
  onChatTopBarChange: (key: keyof ChatTopBarPreferences, visible: boolean) => void;
  calendar: CalendarPreferences;
  onCalendarChange: (key: keyof CalendarPreferences, value: boolean) => void;
}

/** 功能设置统一页面，保持四个业务入口的信息结构与空状态一致。 */
export function FeatureSettingsPage({
  tab,
  studyWindowMode,
  onStudyWindowModeChange,
  chatTopBar,
  onChatTopBarChange,
  calendar,
  onCalendarChange,
}: FeatureSettingsPageProps) {
  if (tab === 'record') {
    return (
      <SettingsPageLayout title="记录" description="">
        <SettingsGroup title="学习计时">
          <SettingsRow
            label="学习小窗"
            labelFor="study-window-mode"
            description="选择小窗后，每次开始学习都会自动展示；关闭或隐藏小窗不会结束当前学习。"
            control={<Select
              id="study-window-mode"
              fieldSize="md"
              value={studyWindowMode}
              onChange={(event) => onStudyWindowModeChange(event.target.value as StudyWindowMode)}
              options={[
                { label: '仅记录页面', value: 'page' },
                { label: '应用内右下角', value: 'in-app' },
                { label: '系统桌面右下角', value: 'desktop' },
              ]}
            />}
          />
        </SettingsGroup>
      </SettingsPageLayout>
    );
  }

  if (tab === 'calendar') {
    return (
      <SettingsPageLayout title="日历" description={FEATURE_CONTENT.calendar.description}>
        <SettingsGroup title="聊天顶栏" description="控制日历信息在聊天页面中的快捷展示。">
          <SettingToggle
            label="显示日期"
            description="显示今天的日期和星期，点击后展开完整日期信息。"
            checked={chatTopBar.showDate}
            onChange={(checked) => onChatTopBarChange('showDate', checked)}
          />
          <SettingToggle
            label="显示待办"
            description="显示今日待办完成情况，点击后查看接下来要处理的事项。"
            checked={chatTopBar.showTodos}
            onChange={(checked) => onChatTopBarChange('showTodos', checked)}
          />
        </SettingsGroup>

        <SettingsGroup title="周期待办便签" description="管理日历右下角本周、本月待办便签的展示方式。">
          <SettingToggle
            label="显示便签"
            description="关闭后隐藏全部周期待办便签，不会影响待办数据。"
            checked={calendar.showStickyNotes}
            onChange={(checked) => onCalendarChange('showStickyNotes', checked)}
          />
          <SettingToggle
            label="默认展开"
            description="开启后周、月便签默认完整展开；修改后立即应用到两张便签。"
            checked={calendar.stickyNotesDefaultExpanded}
            onChange={(checked) => onCalendarChange('stickyNotesDefaultExpanded', checked)}
          />
        </SettingsGroup>
      </SettingsPageLayout>
    );
  }

  if (tab === 'finance') {
    return (
      <SettingsPageLayout title="财务" description={FEATURE_CONTENT.finance.description}>
        <SettingsGroup title="聊天顶栏">
          <SettingToggle
            label="显示财务摘要"
            description="显示今日收入和支出，点击后查看本月汇总与最近流水。"
            checked={chatTopBar.showFinance}
            onChange={(checked) => onChatTopBarChange('showFinance', checked)}
          />
        </SettingsGroup>
      </SettingsPageLayout>
    );
  }

  const content = FEATURE_CONTENT[tab];
  return (
    <SettingsPageLayout title={content.title} description={content.description}>
      <WritingEffectSettings />
    </SettingsPageLayout>
  );
}

function SettingToggle({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <SettingsRow
      label={label}
      description={description}
      control={<LeverSwitch checked={checked} onChange={onChange} label={label} showLabel={false} />}
    />
  );
}
