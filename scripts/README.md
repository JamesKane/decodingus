# Sample template for redirects during extended maintenance

```
  # Maintenance mode - uncomment during migration
  # location / {
  #     root /path/to/decodingus/scripts;
  #     try_files /maintenance.html =503;
  # }

  Or create a separate config:

  server {
      listen 80;
      server_name your-domain.com;

      root /path/to/decodingus/scripts;

      location / {
          try_files /maintenance.html =503;
      }
  }
```