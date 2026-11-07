import { Fragment, type ReactNode } from 'react'

/** Bold (**text**) and links ([text](/path)) inside a line. */
function inline(text: string): ReactNode[] {
  const out: ReactNode[] = []
  const re = /\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)\s]+)\)/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index))
    if (m[1]) out.push(<strong key={m.index}>{m[1]}</strong>)
    else out.push(<a key={m.index} href={m[3]} target={m[3].startsWith('http') ? '_blank' : undefined} rel="noreferrer">{m[2]}</a>)
    last = m.index + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

/**
 * The few Markdown forms the help articles and legal texts use: headings (#, ##, ###), paragraphs, bullet and
 * numbered lists, quotes (>), bold and links. Text only: no HTML is ever interpreted.
 */
export function Markdown({ text }: { text: string }) {
  const blocks: ReactNode[] = []
  const lines = text.replace(/\r/g, '').split('\n')
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) { i++; continue }
    const heading = /^(#{1,3})\s+(.*)$/.exec(line)
    if (heading) {
      const level = heading[1].length
      const content = inline(heading[2])
      blocks.push(level === 1 ? <h2 key={i}>{content}</h2> : level === 2 ? <h3 key={i}>{content}</h3> : <h4 key={i}>{content}</h4>)
      i++
      continue
    }
    const list = /^(\s*)([-*]|\d+\.)\s+/.exec(line)
    if (list) {
      const ordered = /\d/.test(list[2])
      const items: string[] = []
      while (i < lines.length && /^(\s*)([-*]|\d+\.)\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^(\s*)([-*]|\d+\.)\s+/, ''))
        i++
      }
      const lis = items.map((it, n) => <li key={n}>{inline(it)}</li>)
      blocks.push(ordered ? <ol key={i}>{lis}</ol> : <ul key={i}>{lis}</ul>)
      continue
    }
    if (line.startsWith('>')) {
      const quote: string[] = []
      while (i < lines.length && lines[i].startsWith('>')) { quote.push(lines[i].replace(/^>\s?/, '')); i++ }
      blocks.push(<blockquote key={i}>{inline(quote.join(' '))}</blockquote>)
      continue
    }
    const para: string[] = []
    while (i < lines.length && lines[i].trim() && !/^(#{1,3}\s|>|\s*([-*]|\d+\.)\s)/.test(lines[i])) { para.push(lines[i]); i++ }
    blocks.push(<p key={i}>{inline(para.join(' '))}</p>)
  }
  return <div className="prose">{blocks.map((b, n) => <Fragment key={n}>{b}</Fragment>)}</div>
}
