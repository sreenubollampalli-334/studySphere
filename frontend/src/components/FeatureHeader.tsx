import type { ReactNode, Ref } from 'react'

export function FeatureHeader({ title, description, headingId, headingRef, children }: {
  title: string
  description?: string
  headingId?: string
  headingRef?: Ref<HTMLHeadingElement>
  children?: ReactNode
}) {
  return (
    <header className="feature-header">
      <div>
        <h1 ref={headingRef} id={headingId} tabIndex={-1}>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {children}
    </header>
  )
}
