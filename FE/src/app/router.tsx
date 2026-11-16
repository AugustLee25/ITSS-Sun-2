import { lazy, Suspense } from 'react'
import { createBrowserRouter, createMemoryRouter, Navigate, type RouteObject } from 'react-router'
import { RequireAuth } from './RequireAuth'
import { ForbiddenState, GroupNotFound, NotFoundPage, RouteErrorPage } from './ErrorPages'
import { GroupLayout, RootLayout, UserLayout } from './layouts'
import { ModalBackground } from './modal'
import { patterns, paths } from './routes'

/** Mỗi màn hình tải lười (lazy) từ `src/pages/SXX-*`; agent phụ trách chỉ sửa trong thư mục của mình. */
const S00 = lazy(() => import('@/pages/S00-Login'))
const S01 = lazy(() => import('@/pages/S01-Groups'))
const S02 = lazy(() => import('@/pages/S02-GroupSetup'))
const S03 = lazy(() => import('@/pages/S03-Overview'))
const S04 = lazy(() => import('@/pages/S04-Tasks'))
const S05 = lazy(() => import('@/pages/S05-TaskDetail'))
const S06 = lazy(() => import('@/pages/S06-Checkpoint'))
const S07 = lazy(() => import('@/pages/S07-Difficulty'))
const S08 = lazy(() => import('@/pages/S08-HelpBoard'))
const S09 = lazy(() => import('@/pages/S09-HelpDetail'))
const S10 = lazy(() => import('@/pages/S10-HelpExecution'))
const S11 = lazy(() => import('@/pages/S11-Review'))
const S12 = lazy(() => import('@/pages/S12-Points'))
const S13 = lazy(() => import('@/pages/S13-Notifications'))
const Kit = import.meta.env.DEV ? lazy(() => import('@/pages/__kit/KitPage')) : null

export const routes: RouteObject[] = [
  {
    element: <RootLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { path: '/', element: <Navigate to={paths.groups()} replace /> },

      // S00 — công khai
      {
        path: patterns.login,
        element: (
          <Suspense fallback={null}>
            <S00 />
          </Suspense>
        ),
      },

      // Trang kiểm tra component (chỉ DEV)
      ...(Kit
        ? [
            {
              path: '__kit',
              element: (
                <Suspense fallback={null}>
                  <Kit />
                </Suspense>
              ),
            },
          ]
        : []),

      // Vùng cần đăng nhập
      {
        element: <RequireAuth />,
        children: [
          // Ngoài nhóm: S01, S02, S13 (khung chỉ có header)
          {
            element: <UserLayout />,
            children: [
              { path: patterns.groups, element: <S01 /> },
              { path: patterns.groupNew, element: <S02 /> },
              { path: patterns.groupJoin, element: <S02 /> },
              { path: patterns.joinByCode, element: <S02 /> },
              { path: patterns.notifications, element: <S13 /> },
              { path: patterns.forbidden, element: <ForbiddenState /> },
            ],
          },

          // Trong nhóm: /groups/:groupId/...
          {
            path: patterns.group,
            element: <GroupLayout />,
            children: [
              { index: true, element: <S03 /> },
              { path: patterns.settings, element: <S02 /> },
              { path: patterns.tasks, element: <S04 /> },
              { path: patterns.task, element: <S05 /> },
              // S06/S07: route modal — mở trực tiếp được, vẽ màn gọi phía sau
              {
                path: patterns.taskCheckpoint,
                element: (
                  <ModalBackground background={<S05 />}>
                    <S06 />
                  </ModalBackground>
                ),
              },
              {
                path: patterns.checkpoint,
                element: (
                  <ModalBackground background={<S03 />}>
                    <S06 />
                  </ModalBackground>
                ),
              },
              {
                path: patterns.taskDifficulty,
                element: (
                  <ModalBackground background={<S05 />}>
                    <S07 />
                  </ModalBackground>
                ),
              },
              {
                path: patterns.difficulty,
                element: (
                  <ModalBackground background={<S03 />}>
                    <S07 />
                  </ModalBackground>
                ),
              },
              { path: patterns.help, element: <S08 /> },
              { path: patterns.helpRequest, element: <S09 /> },
              { path: patterns.helpWork, element: <S10 /> },
              { path: patterns.helpReview, element: <S11 /> },
              { path: patterns.points, element: <S12 /> },
              { path: '*', element: <GroupNotFound /> },
            ],
          },
        ],
      },

      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

export const createAppRouter = () => createBrowserRouter(routes)
export const createTestRouter = (initialEntries: string[]) => createMemoryRouter(routes, { initialEntries })
