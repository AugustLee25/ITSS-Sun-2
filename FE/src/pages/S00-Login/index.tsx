import { useMemo, useState, type KeyboardEvent } from 'react'
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { login, loginWithGoogle, register, useAuthConfig, useDemoAccounts, useInvitePreview } from '@/api/auth'
import { applyFieldErrors, isApiError } from '@/api/errors'
import { ErrorCode } from '@/api/types'
import { useAuth } from '@/app/auth/authContext'
import { resolvePostLoginPath } from '@/app/redirect'
import { Button, FormField, InlineError, Input } from '@/components'
import { useT, type TFunction } from '@/i18n'
import { cx } from '@/lib/cx'
import { GOOGLE_CLIENT_ID, GoogleSignInError, requestGoogleIdToken } from './googleSignIn'
import styles from './Login.module.css'

type Mode = 'login' | 'register'

interface FormValues {
  name: string
  email: string
  password: string
}

function buildSchema(t: TFunction, mode: Mode, minPassword: number) {
  const email = z.string().trim().min(1, t('form.requiredField', { field: t('login.email') })).pipe(z.email(t('form.invalidEmail')))
  if (mode === 'login') {
    return z.object({
      name: z.string(),
      email,
      password: z.string().min(1, t('form.requiredField', { field: t('login.password') })),
    })
  }
  return z.object({
    name: z
      .string()
      .trim()
      .min(1, t('form.requiredField', { field: t('login.name') }))
      .max(80, t('form.tooLong', { field: t('login.name'), max: 80 })),
    email,
    password: z
      .string()
      .min(minPassword, t('form.tooShort', { field: t('login.password'), min: minPassword }))
      .max(72, t('form.tooLong', { field: t('login.password'), max: 72 })),
  })
}

export default function S00Login() {
  const t = useT()
  const auth = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const invite = params.get('invite')

  const [mode, setMode] = useState<Mode>('login')
  const [googleError, setGoogleError] = useState<string | null>(null)
  const [googleBusy, setGoogleBusy] = useState(false)

  const config = useAuthConfig()
  const minPassword = config.data?.passwordMinLength ?? 8
  const demo = useDemoAccounts(config.data?.demoMode === true)
  const invitePreview = useInvitePreview(invite, { anonymous: true })

  const schema = useMemo(() => buildSchema(t, mode, minPassword), [t, mode, minPassword])
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', email: '', password: '' },
    mode: 'onTouched',
  })

  const finish = (response: Parameters<typeof auth.signIn>[0]) => {
    auth.signIn(response)
    navigate(resolvePostLoginPath(location.search), { replace: true })
  }

  const submit = useMutation({
    mutationFn: (values: FormValues) =>
      mode === 'login'
        ? login({ email: values.email.trim(), password: values.password })
        : register({ name: values.name.trim(), email: values.email.trim(), password: values.password }),
    onSuccess: finish,
    onError: (error) => {
      if (isApiError(error) && error.code === ErrorCode.EMAIL_ALREADY_REGISTERED) {
        form.setError('email', { type: 'server', message: t('login.emailTaken') })
        return
      }
      applyFieldErrors(error, form.setError)
    },
  })

  if (auth.status === 'authenticated' && !submit.isPending) {
    return <Navigate to={resolvePostLoginPath(location.search)} replace />
  }

  const googleEnabled = config.data?.googleEnabled === true
  const googleReady = googleEnabled && !!GOOGLE_CLIENT_ID
  const googleHint = !googleEnabled ? t('login.googleDisabled') : !GOOGLE_CLIENT_ID ? t('login.googleNoClient') : null

  async function onGoogle() {
    if (!GOOGLE_CLIENT_ID || googleBusy) return
    setGoogleError(null)
    setGoogleBusy(true)
    try {
      const idToken = await requestGoogleIdToken(GOOGLE_CLIENT_ID)
      finish(await loginWithGoogle({ idToken }))
    } catch (error) {
      if (error instanceof GoogleSignInError && error.reason === 'blocked') setGoogleError(t('login.googleBlocked'))
      else setGoogleError(t('login.googleFailed'))
    } finally {
      setGoogleBusy(false)
    }
  }

  function switchMode(next: Mode) {
    if (next === mode) return
    setMode(next)
    submit.reset()
    form.clearErrors()
  }

  function onTabKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      switchMode(mode === 'login' ? 'register' : 'login')
    }
  }

  const topError = submit.error && !(isApiError(submit.error) && submit.error.fieldErrors.length > 0) ? submit.error : null
  const credentialError =
    isApiError(topError) && topError.code === ErrorCode.INVALID_CREDENTIALS ? t('login.invalidCredentials') : undefined
  const isEmailTaken = isApiError(topError) && topError.code === ErrorCode.EMAIL_ALREADY_REGISTERED

  return (
    <main id="main-content" tabIndex={-1} className={styles.page}>
      <div className={styles.card}>
        <header className={styles.brand}>
          <span className={styles.mark} aria-hidden="true">
            H
          </span>
          <div>
            <h1 className={styles.wordmark}>{t('app.name')}</h1>
            <p className={styles.tagline}>{t('login.tagline')}</p>
          </div>
        </header>

        {invite ? (
          <p className={cx(styles.notice, invitePreview.isError && styles.noticeMuted)} role="status">
            {invitePreview.data
              ? t('login.invitedBanner', { group: invitePreview.data.groupName })
              : invitePreview.isError
                ? t('login.invitedUnavailable')
                : t('login.invitedBannerGeneric')}
          </p>
        ) : null}
        {auth.sessionEnd === 'expired' && !invite ? (
          <p className={styles.notice} role="status">
            {t('login.sessionExpired')}
          </p>
        ) : null}

        <div className={styles.google}>
          <Button
            variant="secondary"
            size="lg"
            fullWidth
            onClick={() => void onGoogle()}
            disabled={!googleReady}
            loading={googleBusy}
            title={googleHint ?? undefined}
            aria-describedby={googleHint || googleError ? 'google-note' : undefined}
          >
            {t('login.google')}
          </Button>
          {googleHint || googleError ? (
            <p id="google-note" className={styles.hint} role={googleError ? 'alert' : undefined}>
              {googleError ?? googleHint}
            </p>
          ) : null}
        </div>

        <div className={styles.divider} role="separator">
          <span>{t('login.orEmail')}</span>
        </div>

        <div role="tablist" aria-label={t('login.modeLabel')} className={styles.tabs}>
          {(['login', 'register'] as const).map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              id={`tab-${value}`}
              aria-selected={mode === value}
              aria-controls="login-panel"
              tabIndex={mode === value ? 0 : -1}
              className={cx(styles.tab, mode === value && styles.tabActive)}
              onClick={() => switchMode(value)}
              onKeyDown={onTabKeyDown}
            >
              {value === 'login' ? t('login.tabLogin') : t('login.tabRegister')}
            </button>
          ))}
        </div>

        <form
          id="login-panel"
          role="tabpanel"
          aria-labelledby={`tab-${mode}`}
          noValidate
          className={styles.form}
          onSubmit={form.handleSubmit((values) => {
            if (!submit.isPending) submit.mutate(values)
          })}
        >
          {mode === 'register' ? (
            <FormField label={t('login.name')} required error={form.formState.errors.name?.message}>
              <Input autoComplete="name" placeholder={t('login.namePlaceholder')} {...form.register('name')} />
            </FormField>
          ) : null}
          <FormField label={t('login.email')} required error={form.formState.errors.email?.message}>
            <Input
              type="email"
              inputMode="email"
              autoComplete={mode === 'login' ? 'username' : 'email'}
              placeholder={t('login.emailPlaceholder')}
              {...form.register('email')}
            />
          </FormField>
          <FormField
            label={t('login.password')}
            required
            hint={mode === 'register' ? t('login.passwordHint', { min: minPassword }) : undefined}
            error={form.formState.errors.password?.message}
          >
            <Input type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} {...form.register('password')} />
          </FormField>

          {topError && !isEmailTaken ? (
            <InlineError message={credentialError} error={topError} />
          ) : null}

          <Button type="submit" size="lg" fullWidth loading={submit.isPending}>
            {submit.isPending ? t('login.submitting') : mode === 'login' ? t('login.submitLogin') : t('login.submitRegister')}
          </Button>
        </form>

        {mode === 'login' && demo.data && demo.data.items.length > 0 ? (
          <section className={styles.demo} aria-labelledby="demo-title">
            <h2 id="demo-title" className={styles.demoTitle}>
              {t('login.demoTitle')}
            </h2>
            <p className={styles.hint}>{t('login.demoHint')}</p>
            <ul className={styles.demoList}>
              {demo.data.items.map((account) => (
                <li key={account.userId}>
                  <Button
                    variant="secondary"
                    fullWidth
                    onClick={() => {
                      form.setValue('email', account.email, { shouldValidate: true })
                      form.setValue('password', account.password, { shouldValidate: true })
                      submit.reset()
                    }}
                  >
                    {t('login.demoFill', { name: account.name })}
                  </Button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <p className={styles.privacy}>{t('login.privacy')}</p>
      </div>
    </main>
  )
}
