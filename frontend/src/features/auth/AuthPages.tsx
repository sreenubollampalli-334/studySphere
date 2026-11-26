import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import {
  AuthError, deleteAccount, messageFor, register, requestPasswordReset, resetPassword,
  signIn, signOut, verifyEmail,
} from './authApi'
import type { Account } from './authApi'

function EmailField() {
  return (
    <div className="auth-field">
      <label htmlFor="email">Email address</label>
      <input id="email" name="email" type="email" autoComplete="username" required />
    </div>
  )
}

function PasswordField({ newPassword = false, autoFocus = false }: {
  newPassword?: boolean
  autoFocus?: boolean
}) {
  return (
    <div className="auth-field">
      <label htmlFor="password">{newPassword ? 'New password' : 'Password'}</label>
      <input
        id="password"
        name="password"
        type="password"
        autoFocus={autoFocus}
        autoComplete={newPassword ? 'new-password' : 'current-password'}
        aria-describedby={newPassword ? 'password-guidance' : undefined}
        required
      />
      {newPassword && <span id="password-guidance" className="field-hint">Use 12 to 128 characters.</span>}
    </div>
  )
}

function field(data: FormData, name: string) {
  return String(data.get(name) ?? '')
}

function validPassword(data: FormData) {
  const password = field(data, 'password')
  if (password.length > 256) throw new AuthError(400, 'PASSWORD_LENGTH')
  const codePoints = [...password].length
  if (codePoints < 12 || codePoints > 128) throw new AuthError(400, 'PASSWORD_LENGTH')
  return password
}

interface AuthFormProps {
  children?: ReactNode
  submit: string
  pending: string
  onSubmit: (data: FormData) => Promise<unknown>
  errorMessage: (error: unknown) => string
  danger?: boolean
  onCancel?: () => void
}

function AuthForm({ children, submit, pending, onSubmit, errorMessage, danger, onCancel }: AuthFormProps) {
  const submitting = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const data = new FormData(event.currentTarget)
    submitting.current = true
    setBusy(true)
    setError('')
    try {
      await onSubmit(data)
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <form className="auth-form" onSubmit={handleSubmit}>
      <fieldset disabled={busy}>
        {children}
        <button className={danger ? 'button-danger' : undefined} type="submit">
          {busy ? pending : submit}
        </button>
        {onCancel && <button type="button" className="button-secondary" onClick={onCancel}>Cancel</button>}
      </fieldset>
      {busy && <span className="visually-hidden" role="status">{pending}</span>}
      {error && <p className="notice notice-error" role="alert">{error}</p>}
    </form>
  )
}

function passwordError(error: unknown, fallback: string) {
  if (error instanceof AuthError && error.code === 'PASSWORD_LENGTH') {
    return 'Use a password between 12 and 128 characters.'
  }
  return messageFor(error, fallback)
}

export function LoginPage({ notice, onLogin }: { notice?: string; onLogin: (account: Account) => void }) {
  return (
    <>
      <h1 tabIndex={-1}>Sign in</h1>
      {notice && <p className="notice" role="status">{notice}</p>}
      <AuthForm
        submit="Sign in"
        pending="Signing in…"
        onSubmit={async (data) => onLogin(await signIn(field(data, 'email'), field(data, 'password')))}
        errorMessage={(error) => {
          if (error instanceof AuthError && error.status === 401) return 'Check your email and password.'
          if (error instanceof AuthError && error.code === 'EMAIL_VERIFICATION_REQUIRED') {
            return 'Verify your email using the link we sent, then sign in again.'
          }
          return messageFor(error, 'We couldn’t sign you in. Try again.')
        }}
      >
        <EmailField />
        <PasswordField />
      </AuthForm>
      <p className="auth-links"><a href="/forgot-password">Forgot password?</a><a href="/register">Create an account</a></p>
    </>
  )
}

export function RegisterPage() {
  const [submitted, setSubmitted] = useState(false)
  return (
    <>
      <h1 tabIndex={-1}>{submitted ? 'Check your inbox' : 'Create your account'}</h1>
      {submitted ? (
        <p className="notice" role="status">If your email is eligible, you’ll receive a verification link.</p>
      ) : (
        <AuthForm
          submit="Create account"
          pending="Creating account…"
          onSubmit={async (data) => { await register(field(data, 'email'), validPassword(data)); setSubmitted(true) }}
          errorMessage={(error) => passwordError(error, 'We couldn’t complete registration. Try again.')}
        >
          <EmailField />
          <PasswordField newPassword />
        </AuthForm>
      )}
      <p className="auth-links"><a href="/login">Back to sign in</a></p>
    </>
  )
}

export function ForgotPasswordPage() {
  const [submitted, setSubmitted] = useState(false)
  return (
    <>
      <h1 tabIndex={-1}>{submitted ? 'Check your inbox' : 'Forgot password'}</h1>
      {submitted ? (
        <p className="notice" role="status">If an account matches that email, you’ll receive a password reset link.</p>
      ) : (
        <>
          <p>Enter the email address for your StudySphere account.</p>
          <AuthForm
            submit="Send reset link"
            pending="Sending…"
            onSubmit={async (data) => { await requestPasswordReset(field(data, 'email')); setSubmitted(true) }}
            errorMessage={(error) => messageFor(error, 'We couldn’t request a reset link. Try again.')}
          >
            <EmailField />
          </AuthForm>
        </>
      )}
      <p className="auth-links"><a href="/login">Back to sign in</a></p>
    </>
  )
}

function useContinuationToken() {
  const [token, setToken] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token') ?? '')
  useEffect(() => {
    if (window.location.hash) {
      window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search)
    }
  }, [])
  return [token, () => setToken('')] as const
}

export function VerifyEmailPage() {
  const [token, clearToken] = useContinuationToken()
  const [verified, setVerified] = useState(false)
  return (
    <>
      <h1 tabIndex={-1}>{verified ? 'Email verified' : 'Verify your email'}</h1>
      {verified ? (
        <p className="notice" role="status">You can now sign in to StudySphere.</p>
      ) : token ? (
        <AuthForm
          submit="Verify email"
          pending="Verifying…"
          onSubmit={async () => { await verifyEmail(token); clearToken(); setVerified(true) }}
          errorMessage={(error) => messageFor(error, 'This verification link is invalid or has expired.')}
        />
      ) : (
        <p role="alert">Open the verification link from your registration email to continue.</p>
      )}
      <p className="auth-links"><a href="/login">Back to sign in</a></p>
    </>
  )
}

export function ResetPasswordPage() {
  const [token, clearToken] = useContinuationToken()
  const [changed, setChanged] = useState(false)
  return (
    <>
      <h1 tabIndex={-1}>{changed ? 'Password reset' : 'Reset your password'}</h1>
      {changed ? (
        <p className="notice" role="status">Sign in with your new password.</p>
      ) : token ? (
        <AuthForm
          submit="Reset password"
          pending="Resetting password…"
          onSubmit={async (data) => { await resetPassword(token, validPassword(data)); clearToken(); setChanged(true) }}
          errorMessage={(error) => passwordError(error, 'This reset link is invalid or has expired.')}
        >
          <PasswordField newPassword />
        </AuthForm>
      ) : (
        <p role="alert">Open a password reset link from your email to continue.</p>
      )}
      <p className="auth-links">
        <a href="/login">Back to sign in</a>
        {!changed && <a href="/forgot-password">Request a new link</a>}
      </p>
    </>
  )
}

export function AccountPage({ account, onExit }: { account: Account; onExit: (notice: string) => void }) {
  const [showDelete, setShowDelete] = useState(false)
  const deleteButton = useRef<HTMLButtonElement>(null)
  const returnDeleteFocus = useRef(false)

  useEffect(() => {
    if (!showDelete && returnDeleteFocus.current) {
      returnDeleteFocus.current = false
      deleteButton.current?.focus()
    }
  }, [showDelete])

  function cancelDelete() {
    returnDeleteFocus.current = true
    setShowDelete(false)
  }
  return (
    <>
      <h1 tabIndex={-1}>Your account</h1>
      <dl className="account-details account-identity">
        <dt>Email address</dt>
        <dd>{account.email}</dd>
      </dl>
      {!showDelete && (
        <AuthForm
          submit="Sign out"
          pending="Signing out…"
          onSubmit={async () => { await signOut(); onExit('You’ve been signed out.') }}
          errorMessage={(error) => messageFor(error, 'We couldn’t sign you out. Try again.')}
        />
      )}
      <section className="account-delete" aria-labelledby="delete-heading" onKeyDown={event => {
        if (event.key === 'Escape' && showDelete && !event.currentTarget.querySelector('fieldset:disabled')) {
          event.preventDefault()
          cancelDelete()
        }
      }}>
        <h2 id="delete-heading">Delete account</h2>
        {showDelete ? (
          <>
            <p>Enter your password to permanently delete your StudySphere account.</p>
            <AuthForm
              submit="Permanently delete account"
              pending="Deleting account…"
              danger
              onCancel={cancelDelete}
              onSubmit={async (data) => {
                await deleteAccount(account.email, field(data, 'password'))
                onExit('Your account has been deleted.')
              }}
              errorMessage={(error) => {
                if (error instanceof AuthError && error.status === 401) return 'Check your password and try again.'
                return messageFor(error, 'We couldn’t delete your account. Try again.')
              }}
            >
              <PasswordField autoFocus />
            </AuthForm>
          </>
        ) : (
          <button ref={deleteButton} type="button" className="button-secondary" onClick={() => setShowDelete(true)}>Delete account</button>
        )}
      </section>
    </>
  )
}
