import { useEffect, useState } from 'react'
import {
  AccountPage, ForgotPasswordPage, LoginPage, RegisterPage, ResetPasswordPage, VerifyEmailPage,
} from './AuthPages'
import { AuthError, messageFor, restoreAccount } from './authApi'
import type { Account } from './authApi'
import { isCommunityPath, isRoomPath, StudentWorkspace, workspacePaths } from '../student/StudentWorkspace'
import { isDocumentPath } from '../documents/DocumentsPage'

function currentPath() {
  return window.location.pathname.replace(/\/$/, '') || '/'
}

export function AuthApp() {
  const [path, setPath] = useState(currentPath)
  const [account, setAccount] = useState<Account>()
  const [notice, setNotice] = useState<string>()
  const [loadError, setLoadError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const workspacePath = path === '/' ? '/dashboard' : path
  const protectedPage = path === '/account' || workspacePaths.includes(workspacePath)
    || isRoomPath(workspacePath) || isDocumentPath(workspacePath) || isCommunityPath(workspacePath)

  useEffect(() => {
    function handlePopState() {
      setPath(currentPath())
      setNotice(undefined)
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  useEffect(() => {
    const content = document.getElementById('auth-content')
    if (!content) return

    function focusHeading() {
      const heading = content?.querySelector('h1')
      if (!heading) return false
      heading.tabIndex = -1
      heading.focus()
      return true
    }

    if (focusHeading()) return
    const observer = new MutationObserver(() => {
      if (focusHeading()) observer.disconnect()
    })
    observer.observe(content, { childList: true, subtree: true })
    return () => observer.disconnect()
  }, [path, account, loadError])

  useEffect(() => {
    if (!protectedPage || account) return
    let active = true

    restoreAccount().then(
      (loadedAccount) => { if (active) setAccount(loadedAccount) },
      (error: unknown) => {
        if (!active) return
        if (error instanceof AuthError && error.status === 401) {
          window.history.replaceState(null, '', '/login')
          setPath('/login')
        } else {
          setLoadError(messageFor(error, 'We couldn’t load your account. Try again.'))
        }
      },
    )
    return () => { active = false }
  }, [protectedPage, account, attempt])

  function handleLogin(nextAccount: Account) {
    setAccount(nextAccount)
    setNotice(undefined)
    setLoadError('')
    window.history.replaceState(null, '', '/account')
    setPath('/account')
  }

  function handleExit(nextNotice: string) {
    setAccount(undefined)
    setNotice(nextNotice)
    window.history.replaceState(null, '', '/login')
    setPath('/login')
  }

  function navigate(nextPath: string) {
    window.history.pushState(null, '', nextPath)
    setPath(nextPath)
  }

  let page
  if (protectedPage) {
    if (account) {
      page = path === '/account'
        ? <><AccountPage account={account} onExit={handleExit} />
          <p className="auth-links"><button onClick={() => navigate('/dashboard')}>Open dashboard</button>
            <button className="button-secondary" onClick={() => navigate('/onboarding')}>Set up your profile</button></p></>
        : <StudentWorkspace key={workspacePath} path={workspacePath} navigate={navigate}
          isAdmin={account.roles.includes('ADMIN')} />
    } else if (loadError) {
      page = (
        <>
          <h1 tabIndex={-1}>Your account</h1>
          <p className="notice notice-error" role="alert">{loadError}</p>
          <button onClick={() => { setLoadError(''); setAttempt((previous) => previous + 1) }}>Try again</button>
          <p className="auth-links"><a href="/login">Back to sign in</a></p>
        </>
      )
    } else {
      page = (
        <>
          <h1 tabIndex={-1}>Your account</h1>
          <p role="status">Checking your session…</p>
        </>
      )
    }
  } else {
    switch (path) {
      case '/login': page = <LoginPage notice={notice} onLogin={handleLogin} />; break
      case '/register': page = <RegisterPage />; break
      case '/forgot-password': page = <ForgotPasswordPage />; break
      case '/verify-email': page = <VerifyEmailPage />; break
      case '/reset-password': page = <ResetPasswordPage />; break
      default: page = (
        <>
          <h1 tabIndex={-1}>Page not found</h1>
          <p className="auth-links"><a href="/">Go to StudySphere</a></p>
        </>
      )
    }
  }

  const inWorkspace = Boolean(account && path !== '/account')

  return (
    <div className={inWorkspace ? 'workspace-shell' : 'account-shell'}>
      <a className="skip-link" href="#auth-content" onClick={(event) => {
        event.preventDefault()
        document.getElementById('auth-content')?.focus()
      }}>Skip to content</a>
      <header className="site-header">
        <a className="brand" href="/">
          <img src="/studysphere-mark.webp" alt="" width="40" height="40" />
          <span>Study<span className="brand-accent">Sphere</span></span>
        </a>
      </header>
      {inWorkspace ? page : account && path === '/account' ? (
        <main className="account-card account-management" id="auth-content" tabIndex={-1}>{page}</main>
      ) : (
        <div className="auth-layout">
          <aside className="auth-brand">
            <div className="brand-orbit" aria-hidden="true">
              <img src="/studysphere-mark.webp" alt="" width="260" height="260" />
            </div>
            <p>Learn Smarter. Together.</p>
          </aside>
          <main className="account-card" id="auth-content" tabIndex={-1}>{page}</main>
        </div>
      )}
    </div>
  )
}
