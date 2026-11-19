import type { RecordEntry, RecordType } from '../../types/record';
import { CalendarView } from '../calendar/CalendarView';
import { FinancePage } from '../finance/FinancePage';
import { MaterialsPage } from '../materials/MaterialsPage';
import { RecordPage } from '../record/RecordPage';
import { LifeStatusPage } from '../review/status/LifeStatusPage';
import { StudyPage } from '../study/StudyPage';
import { TaskPage } from '../task/TaskPage';
import type { SidebarFeatureId } from './sidebarFeatures';

interface FeatureWorkspaceViewProps {
  activeFeature: Exclude<SidebarFeatureId, 'chat'>;
  recordTarget: RecordEntry | null | undefined;
  recordInitialType: RecordType;
  existingSessionIds: Set<string>;
  onOpenSession: (sessionId: string) => void;
  onOpenSettings: (tab: string) => void;
}

export function FeatureWorkspaceView({
  activeFeature,
  recordTarget,
  recordInitialType,
  existingSessionIds,
  onOpenSession,
  onOpenSettings,
}: FeatureWorkspaceViewProps) {
  switch (activeFeature) {
    case 'task':
      return <TaskPage onOpenSettings={() => onOpenSettings('task')} />;
    case 'status':
      return <LifeStatusPage onOpenSettings={(tab = 'review') => onOpenSettings(tab)} />;
    case 'calendar':
      return <CalendarView />;
    case 'finance':
      return <FinancePage />;
    case 'record':
      return <RecordPage initialEntry={recordTarget} initialType={recordInitialType} />;
    case 'materials':
      return <MaterialsPage existingSessionIds={existingSessionIds} onOpenSession={onOpenSession} />;
    case 'study':
      return <StudyPage />;
  }
}
