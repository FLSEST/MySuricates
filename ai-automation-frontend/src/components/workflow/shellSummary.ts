/** Short line of a shell block: its command, or in tool mode (`container_args`) image and arguments. */
export function shellSummary(config: Record<string, unknown>): string {
  const args = config.container_args;
  if (Array.isArray(args)) {
    return [config.container_image, ...args]
      .filter((part) => part !== undefined && part !== null && part !== '')
      .map(String)
      .join(' ');
  }
  return String(config.command ?? '');
}
