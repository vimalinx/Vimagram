export type MachineRoutingConfig = {
  modeAccountMap?: Record<string, string>;
  modeModelHints?: Record<string, string>;
  modeAgentHints?: Record<string, string>;
  modeSkillsHints?: Record<string, string>;
};

export type MachineMinimaxProviderSync = {
  endpoint?: "global" | "cn";
  modelId?: string;
  apiKey?: string;
};

export type MachineProviderSyncConfig = {
  minimax?: MachineMinimaxProviderSync;
};

export type RegisteredMachineProfile = {
  machineId: string;
  routing?: MachineRoutingConfig;
  providerSync?: MachineProviderSyncConfig;
  updatedAt?: number;
  lastSeenAt?: number;
};

const machineProfiles = new Map<string, RegisteredMachineProfile>();

export function setRegisteredMachineProfile(
  accountId: string,
  profile: RegisteredMachineProfile | null,
): void {
  if (!profile) {
    machineProfiles.delete(accountId);
    return;
  }
  machineProfiles.set(accountId, profile);
}

export function getRegisteredMachineProfile(
  accountId: string,
): RegisteredMachineProfile | undefined {
  return machineProfiles.get(accountId);
}

export function clearRegisteredMachineProfile(accountId: string): void {
  machineProfiles.delete(accountId);
}
