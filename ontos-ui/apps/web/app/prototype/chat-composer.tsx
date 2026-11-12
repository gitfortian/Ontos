import { Button, Textarea } from "@ontos/ui";
import { ArrowUp, Database, Pause, Play } from "lucide-react";
import type { KeyboardEvent } from "react";

interface ChatComposerProps {
  draft: string;
  disabled: boolean;
  running: boolean;
  following: boolean;
  placeholder?: string;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onToggleFollowing: () => void;
}

export function ChatComposer({
  draft,
  disabled,
  running,
  following,
  placeholder = "继续追问，或换一个数据问题…",
  onDraftChange,
  onSend,
  onToggleFollowing,
}: ChatComposerProps) {
  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    if (!disabled && !running && draft.trim()) onSend();
  };

  return (
    <div className="mx-auto w-full max-w-[920px]">
      <div className="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-2 shadow-sm focus-within:border-[var(--ring)] focus-within:ring-2 focus-within:ring-[var(--primary-subtle)]">
        <Textarea
          aria-label="输入数据问题"
          value={draft}
          onValueChange={onDraftChange}
          onKeyDown={handleKeyDown}
          disabled={disabled || running}
          placeholder={placeholder}
          rows={2}
          className="max-h-32 min-h-[58px] resize-none border-0 bg-transparent px-3 py-2 shadow-none focus-visible:ring-0"
        />
        <div className="flex items-center gap-2 px-1 pb-0.5">
          <div className="flex items-center gap-1.5 rounded-full bg-[var(--muted)] px-2.5 py-1 text-[11px] text-[var(--muted-foreground)]">
            <Database size={12} aria-hidden="true" />
            销售数据域
          </div>
          <span className="hidden text-[11px] text-[var(--muted-foreground)] sm:inline">
            Enter 发送 · Shift + Enter 换行
          </span>
          <div className="ml-auto flex items-center gap-2">
            {running ? (
              <Button variant="ghost" size="small" onClick={onToggleFollowing}>
                {following ? (
                  <Pause size={13} aria-hidden="true" />
                ) : (
                  <Play size={13} aria-hidden="true" />
                )}
                {following ? "停止跟随" : "重新跟随"}
              </Button>
            ) : null}
            <Button
              variant="primary"
              size="small"
              className="size-8 rounded-full p-0"
              aria-label="发送问题"
              disabled={disabled || running || !draft.trim()}
              onClick={onSend}
            >
              <ArrowUp size={16} aria-hidden="true" />
            </Button>
          </div>
        </div>
      </div>
      <p className="mt-2 text-center text-[11px] text-[var(--muted-foreground)]">
        结果由语义口径与权限策略约束，重要决策请复核原始数据
      </p>
    </div>
  );
}
