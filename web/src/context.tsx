import { createContext, useContext } from 'react';
import type { User, View } from './types';
export const WorkspaceContext = createContext<{
  user: User;
  navigate: (view: View, target?: string) => void;
  notify: (message: string, error?: boolean) => void;
  canEdit: boolean;
}>({ user: {} as User, navigate: () => {}, notify: () => {}, canEdit: false });
export const useWorkspace = () => useContext(WorkspaceContext);
