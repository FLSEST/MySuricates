/**
 * A variable name as words: `project_name` → "Project name", `repoUrl` → "Repo url",
 * `PR_URL` → "PR URL". Acronyms (all caps, two letters or more) keep their case.
 */
export function humanizeName(name: string): string {
  const words = name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[\s_.-]+/)
    .filter(Boolean)
    .map((word) => (word.length > 1 && word === word.toUpperCase() && /[A-Z]/.test(word) ? word : word.toLowerCase()));
  if (words.length === 0) return name;
  const text = words.join(' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}
