# dist/ = npm run build de la ui; nginx sirve la SPA y pasa /api al Core
FROM nginx:1.29-alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
