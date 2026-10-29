server {
  listen 80;
  client_max_body_size 50m;
  location /api/ { proxy_pass http://__API__; proxy_set_header Host $host; proxy_read_timeout 600s; }
  # los chunks con hash de vite: su nombre cambia con su contenido, se guardan un año. uno que falta es un 404, nunca el
  # index.html (un index.html viejo pide chunks de un despliegue anterior)
  location /assets/ { root /usr/share/nginx/html; try_files $uri =404; add_header Cache-Control "public, max-age=31536000, immutable"; }
  # index.html y el fallback de la SPA se revalidan en cada carga: tras un redespliegue piden los chunks nuevos
  location = /index.html { root /usr/share/nginx/html; add_header Cache-Control "no-cache"; }
  location / { root /usr/share/nginx/html; try_files $uri /index.html; add_header Cache-Control "no-cache"; }
}
