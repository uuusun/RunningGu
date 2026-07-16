// .dc.html STYLE A 에서 쓰인 라인 아이콘 모음(SVG). stroke=currentColor 기본.
const PATHS = {
  back: <polyline points="15 5 8 12 15 19" />,
  chevronRight: <polyline points="9 6 15 12 9 18" />,
  search: <><circle cx="11" cy="11" r="7" /><line x1="20" y1="20" x2="16" y2="16" /></>,
  heart: <path d="M12 20l-1.2-1C5.6 14.5 3 11.6 3 8.2 3 5.6 5 3.6 7.5 3.6c1.6 0 3.2.9 4.5 2.6 1.3-1.7 2.9-2.6 4.5-2.6C19 3.6 21 5.6 21 8.2c0 3.4-2.6 6.3-7.8 10.8z" />,
  share: <><circle cx="18" cy="5" r="2.5" /><circle cx="6" cy="12" r="2.5" /><circle cx="18" cy="19" r="2.5" /><line x1="8" y1="11" x2="16" y2="6.5" /><line x1="8" y1="13" x2="16" y2="17.5" /></>,
  filter: <><line x1="3" y1="6" x2="21" y2="6" /><line x1="6" y1="12" x2="18" y2="12" /><line x1="9" y1="18" x2="15" y2="18" /></>,
  calendar: <><rect x="3" y="4" width="18" height="17" rx="3" /><line x1="3" y1="9" x2="21" y2="9" /><line x1="8" y1="2" x2="8" y2="6" /><line x1="16" y1="2" x2="16" y2="6" /></>,
  route: <><circle cx="6" cy="7" r="2.4" /><circle cx="18" cy="17" r="2.4" /><path d="M6 9.4v3.6a3 3 0 0 0 3 3h6" /></>,
  courses: <><path d="M3 18c3 0 3-9 6-9s3 6 6 6 3-6 6-6" /><circle cx="20" cy="6" r="1.3" fill="currentColor" stroke="none" /></>,
  bookmark: <path d="M6 3h12v18l-6-4-6 4z" />,
  walk: <><circle cx="9" cy="6" r="2" /><path d="M9 8v5l3 4" /></>,
  car: <><rect x="3" y="11" width="18" height="6" rx="2" /><circle cx="7.5" cy="17" r="1.5" /><circle cx="16.5" cy="17" r="1.5" /></>,
  plus: <><line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" /></>,
  x: <><line x1="6" y1="6" x2="18" y2="18" /><line x1="18" y1="6" x2="6" y2="18" /></>,
  refresh: <><path d="M3 12a9 9 0 0 1 15-6.7L21 8" /><path d="M21 3v5h-5" /></>,
  grip: <><line x1="5" y1="9" x2="19" y2="9" /><line x1="5" y1="15" x2="19" y2="15" /></>,
  info: <><circle cx="12" cy="12" r="9" /><line x1="12" y1="11" x2="12" y2="16" /><circle cx="12" cy="8" r="1" fill="currentColor" stroke="none" /></>,
  external: <><path d="M7 17L17 7" /><path d="M8 7h9v9" /></>,
  save: <><path d="M12 20H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h8l6 6" /><path d="M14 4v6h6" /><path d="M18 14v6M15 17h6" /></>,
  recover: <><path d="M3 14h13a4 4 0 0 1 4 4v2" /><path d="M3 9v9" /><path d="M3 12h6a3 3 0 0 0 3-3" /></>,
  pin: <><path d="M12 21s-7-4.5-9.5-9C1 9 2.5 5.5 6 5.5c2 0 3.2 1.3 4 2.5.8-1.2 2-2.5 4-2.5 3.5 0 5 3.5 3.5 6.5C19 16.5 12 21 12 21z" /></>,
  locate: <><circle cx="12" cy="12" r="3" /><line x1="12" y1="2" x2="12" y2="5" /><line x1="12" y1="19" x2="12" y2="22" /><line x1="2" y1="12" x2="5" y2="12" /><line x1="19" y1="12" x2="22" y2="12" /></>,
  check: <polyline points="5 12 10 17 19 7" />,
}

export default function Icon({ name, size = 22, stroke = 2, fill = 'none', className, style }) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={fill}
      stroke="currentColor"
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={style}
      aria-hidden="true"
    >
      {PATHS[name] || null}
    </svg>
  )
}
