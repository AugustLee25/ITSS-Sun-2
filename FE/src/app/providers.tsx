import type { ReactNode } from 'react'
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { ToastProvider } from '@/components'
import { I18nProvider } from '@/i18n'
import { AuthProvider } from './auth/AuthProvider'
import { queryClient as defaultQueryClient } from './queryClient'

/** Thứ tự: i18n → Query → Toast → Auth. Router nằm bên trong (xem App.tsx). */
export function AppProviders({ children, queryClient = defaultQueryClient }: { children: ReactNode; queryClient?: QueryClient }) {
  return (
    <I18nProvider>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthProvider>{children}</AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    </I18nProvider>
  )
}
