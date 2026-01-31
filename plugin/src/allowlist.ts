export type AllowlistMatch = {
  allowed: boolean;
  matchKey?: string;
  matchSource?: "wildcard" | "id" | "name";
};

export function normalizeTestAllowEntry(raw: string): string {
  return raw.trim().toLowerCase().replace(/^vimalinx:/i, "");
}

export function normalizeTestAllowlist(values: Array<string | number> | undefined): string[] {
  return (values ?? []).map((value) => normalizeTestAllowEntry(String(value))).filter(Boolean);
}

export function resolveTestAllowlistMatch(params: {
  allowFrom: Array<string | number> | undefined;
  senderId: string;
  senderName?: string | null;
}): AllowlistMatch {
  const allowFrom = normalizeTestAllowlist(params.allowFrom);
  if (allowFrom.length === 0) return { allowed: false };
  if (allowFrom.includes("*")) {
    return { allowed: true, matchKey: "*", matchSource: "wildcard" };
  }
  const senderId = normalizeTestAllowEntry(params.senderId);
  if (allowFrom.includes(senderId)) {
    return { allowed: true, matchKey: senderId, matchSource: "id" };
  }
  const senderName = params.senderName ? normalizeTestAllowEntry(params.senderName) : "";
  if (senderName && allowFrom.includes(senderName)) {
    return { allowed: true, matchKey: senderName, matchSource: "name" };
  }
  return { allowed: false };
}
