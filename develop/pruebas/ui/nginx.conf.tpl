server {
  listen 80;
  client_max_body_size 50m;
  location /api/ { proxy_pass http://__API__; proxy_set_header Host $host; proxy_read_timeout 600s; }
  location / { root /usr/share/nginx/html; try_files $uri /index.html; }
}
