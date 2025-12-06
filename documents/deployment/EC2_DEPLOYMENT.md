# EC2 Deployment Guide

This guide covers deploying DecodingUs to a raw Amazon EC2 instance running Ubuntu.

## Prerequisites

- AWS account with EC2 access
- SSH key pair created in AWS
- Domain name (optional, for HTTPS)

## Architecture Options

### Option A: Single Instance (Development/Staging)
```
┌─────────────────────────────────────────────────────┐
│                    EC2 Instance                      │
│  ┌─────────┐   ┌─────────────┐   ┌──────────────┐  │
│  │  Nginx  │───│  DecodingUs │───│  PostgreSQL  │  │
│  │  :80    │   │  :9000      │   │  :5432       │  │
│  └─────────┘   └─────────────┘   └──────────────┘  │
└─────────────────────────────────────────────────────┘
```

### Option B: Production (Recommended)
```
┌─────────────────────────────────────────────────────┐
│                    EC2 Instance                      │
│  ┌─────────┐   ┌─────────────┐                      │
│  │  Nginx  │───│  DecodingUs │──────┐              │
│  │  :80    │   │  :9000      │      │              │
│  └─────────┘   └─────────────┘      │              │
└─────────────────────────────────────│──────────────┘
                                      │
                                      ▼
                              ┌──────────────┐
                              │   RDS        │
                              │  PostgreSQL  │
                              └──────────────┘
```

---

## Part 1: EC2 Instance Setup

### 1.1 Launch EC2 Instance

**Recommended Specifications:**
- **AMI:** Ubuntu 24.04 LTS (HVM)
- **Instance Type:** t3.medium (2 vCPU, 4 GB RAM) minimum
- **Storage:** 30 GB gp3 SSD
- **Security Group:** See below

**Security Group Rules:**

| Type  | Protocol | Port | Source          | Description      |
|-------|----------|------|-----------------|------------------|
| SSH   | TCP      | 22   | Your IP         | SSH access       |
| HTTP  | TCP      | 80   | 0.0.0.0/0       | Web traffic      |
| HTTPS | TCP      | 443  | 0.0.0.0/0       | Secure web       |
| Custom| TCP      | 5432 | Instance SG     | PostgreSQL (internal only) |

### 1.2 Connect to Instance

```bash
ssh -i your-key.pem ubuntu@<ec2-public-ip>
```

### 1.3 Initial System Setup

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Set timezone
sudo timedatectl set-timezone UTC

# Install essential packages
sudo apt install -y \
    curl \
    git \
    unzip \
    htop \
    fail2ban \
    ufw
```

### 1.4 Configure Firewall

```bash
# Enable UFW
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow ssh
sudo ufw allow http
sudo ufw allow https
sudo ufw enable
```

---

## Part 2: Install Docker

```bash
# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Add ubuntu user to docker group
sudo usermod -aG docker ubuntu

# Install Docker Compose plugin
sudo apt install -y docker-compose-plugin

# Log out and back in for group changes to take effect
exit
```

Reconnect via SSH, then verify:

```bash
docker --version
docker compose version
```

---

## Part 3: Install PostgreSQL (Option A - On Instance)

Skip this section if using RDS (Option B).

```bash
# Install PostgreSQL with PostGIS
sudo apt install -y postgresql-16 postgresql-16-postgis-3

# Start and enable
sudo systemctl start postgresql
sudo systemctl enable postgresql

# Create database and user
sudo -u postgres psql << 'EOF'
CREATE USER decodingus_user WITH PASSWORD 'your-secure-password';
CREATE DATABASE decodingus_db OWNER decodingus_user;
CREATE DATABASE decodingus_metadata OWNER decodingus_user;

\c decodingus_db
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

\c decodingus_metadata
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

GRANT ALL PRIVILEGES ON DATABASE decodingus_db TO decodingus_user;
GRANT ALL PRIVILEGES ON DATABASE decodingus_metadata TO decodingus_user;
EOF
```

Configure PostgreSQL to accept local connections:

```bash
# Edit pg_hba.conf
sudo nano /etc/postgresql/16/main/pg_hba.conf

# Add this line before other rules:
# host    all    decodingus_user    127.0.0.1/32    scram-sha-256

sudo systemctl restart postgresql
```

---

## Part 4: Deploy Application

### 4.1 Create Application Directory

```bash
sudo mkdir -p /opt/decodingus
sudo chown ubuntu:ubuntu /opt/decodingus
cd /opt/decodingus
```

### 4.2 Clone Repository (or Upload Build)

**Option A: Clone and Build on Server**

```bash
# Install SBT
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt update
sudo apt install -y sbt openjdk-21-jdk

# Clone repository
git clone https://github.com/decodingus/decodingus.git .

# Build application
sbt stage

# Build Docker image
docker build -t decodingus:latest .
```

**Option B: Upload Pre-Built Image**

```bash
# On your local machine, save the image
docker save decodingus:latest | gzip > decodingus-latest.tar.gz

# Upload to EC2
scp -i your-key.pem decodingus-latest.tar.gz ubuntu@<ec2-ip>:/opt/decodingus/

# On EC2, load the image
cd /opt/decodingus
gunzip -c decodingus-latest.tar.gz | docker load
```

### 4.3 Create Environment File

```bash
cat > /opt/decodingus/.env << 'EOF'
# Application
APPLICATION_SECRET=<generate-with-openssl-rand-base64-64>

# Database (adjust for RDS if using Option B)
DATABASE_URL=jdbc:postgresql://localhost:5432/decodingus_db
DATABASE_USER=decodingus_user
DATABASE_PASSWORD=your-secure-password
METADATA_DATABASE_URL=jdbc:postgresql://localhost:5432/decodingus_metadata

# Production settings
ENABLE_RECAPTCHA=true
RECAPTCHA_SITE_KEY=your-site-key
RECAPTCHA_SECRET_KEY=your-secret-key
CONTACT_RECIPIENT_EMAIL=contact@decoding-us.com
EOF

# Secure the file
chmod 600 /opt/decodingus/.env
```

Generate the application secret:

```bash
openssl rand -base64 64 | tr -d '\n'
```

### 4.4 Create Docker Compose Override for EC2

```bash
cat > /opt/decodingus/docker-compose.ec2.yml << 'EOF'
services:
  app:
    image: decodingus:latest
    container_name: decodingus-app
    ports:
      - "127.0.0.1:9000:9000"
    environment:
      - SLICK_DBS_DEFAULT_DB_URL=${DATABASE_URL}
      - SLICK_DBS_DEFAULT_DB_USER=${DATABASE_USER}
      - SLICK_DBS_DEFAULT_DB_PASSWORD=${DATABASE_PASSWORD}
      - SLICK_DBS_METADATA_DB_URL=${METADATA_DATABASE_URL}
      - SLICK_DBS_METADATA_DB_USER=${DATABASE_USER}
      - SLICK_DBS_METADATA_DB_PASSWORD=${DATABASE_PASSWORD}
      - APPLICATION_SECRET=${APPLICATION_SECRET}
      - PLAY_HTTP_SECRET_KEY=${APPLICATION_SECRET}
      - ENABLE_RECAPTCHA=${ENABLE_RECAPTCHA}
      - RECAPTCHA_SECRET_KEY=${RECAPTCHA_SECRET_KEY}
      - RECAPTCHA_SITE_KEY=${RECAPTCHA_SITE_KEY}
      - CONTACT_RECIPIENT_EMAIL=${CONTACT_RECIPIENT_EMAIL}
    extra_hosts:
      - "host.docker.internal:host-gateway"
    restart: always
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "5"
EOF
```

**Note:** If using PostgreSQL on the EC2 instance, change `localhost` in DATABASE_URL to `host.docker.internal`.

### 4.5 Start Application

```bash
cd /opt/decodingus
docker compose -f docker-compose.ec2.yml up -d
```

Verify it's running:

```bash
docker compose -f docker-compose.ec2.yml ps
docker compose -f docker-compose.ec2.yml logs -f app
```

---

## Part 5: Configure Nginx Reverse Proxy

### 5.1 Install Nginx

```bash
sudo apt install -y nginx
```

### 5.2 Create Site Configuration

```bash
sudo tee /etc/nginx/sites-available/decodingus << 'EOF'
server {
    listen 80;
    server_name your-domain.com www.your-domain.com;

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;

    # Logging
    access_log /var/log/nginx/decodingus_access.log;
    error_log /var/log/nginx/decodingus_error.log;

    # Proxy to Play application
    location / {
        proxy_pass http://127.0.0.1:9000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # Health check endpoint (no logging)
    location /health {
        proxy_pass http://127.0.0.1:9000/health;
        access_log off;
    }

    # Static assets caching
    location /assets/ {
        proxy_pass http://127.0.0.1:9000/assets/;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
EOF
```

### 5.3 Enable Site

```bash
sudo ln -s /etc/nginx/sites-available/decodingus /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

---

## Part 6: Enable HTTPS with Let's Encrypt

```bash
# Install Certbot
sudo apt install -y certbot python3-certbot-nginx

# Obtain certificate
sudo certbot --nginx -d your-domain.com -d www.your-domain.com

# Verify auto-renewal
sudo certbot renew --dry-run
```

Certbot automatically configures Nginx for HTTPS and sets up auto-renewal.

---

## Part 7: Create Systemd Service

For managing the application as a system service:

```bash
sudo tee /etc/systemd/system/decodingus.service << 'EOF'
[Unit]
Description=DecodingUs Application
After=docker.service
Requires=docker.service

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/decodingus
ExecStart=/usr/bin/docker compose -f docker-compose.ec2.yml up
ExecStop=/usr/bin/docker compose -f docker-compose.ec2.yml down
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable decodingus
sudo systemctl start decodingus
```

---

## Part 8: Monitoring and Maintenance

### 8.1 View Logs

```bash
# Application logs
docker compose -f docker-compose.ec2.yml logs -f app

# Nginx logs
sudo tail -f /var/log/nginx/decodingus_access.log
sudo tail -f /var/log/nginx/decodingus_error.log
```

### 8.2 Update Application

```bash
cd /opt/decodingus

# Pull latest changes (if building on server)
git pull
sbt stage
docker build -t decodingus:latest .

# Or load new image (if uploading)
gunzip -c decodingus-new.tar.gz | docker load

# Restart
docker compose -f docker-compose.ec2.yml down
docker compose -f docker-compose.ec2.yml up -d
```

### 8.3 Database Backup

```bash
# Backup
pg_dump -h localhost -U decodingus_user decodingus_db | gzip > backup-$(date +%Y%m%d).sql.gz

# Restore
gunzip -c backup-20250101.sql.gz | psql -h localhost -U decodingus_user decodingus_db
```

### 8.4 Health Checks

```bash
# Check application health
curl http://localhost:9000/health

# Check Docker container status
docker compose -f docker-compose.ec2.yml ps

# Check system resources
htop
df -h
```

---

## Troubleshooting

### Application Won't Start

```bash
# Check logs
docker compose -f docker-compose.ec2.yml logs app

# Common issues:
# - DATABASE_URL not accessible from container
# - Missing APPLICATION_SECRET
# - Port 9000 already in use
```

### Database Connection Failed

```bash
# If using host PostgreSQL, ensure pg_hba.conf allows Docker network
# Or use host.docker.internal as the host

# Test connection from container
docker compose -f docker-compose.ec2.yml exec app sh
# Inside container:
curl -v telnet://host.docker.internal:5432
```

### Nginx 502 Bad Gateway

```bash
# Check if app is running
curl http://127.0.0.1:9000/health

# Check nginx config
sudo nginx -t

# Check if port is listening
sudo ss -tlnp | grep 9000
```

---

## Security Checklist

- [ ] SSH key-only authentication (disable password auth)
- [ ] UFW firewall enabled with minimal ports
- [ ] fail2ban configured for SSH
- [ ] APPLICATION_SECRET is a strong random value
- [ ] Database password is strong and unique
- [ ] .env file permissions are 600
- [ ] HTTPS enabled with valid certificate
- [ ] Regular security updates: `sudo apt update && sudo apt upgrade`
- [ ] Database backups scheduled
- [ ] Log rotation configured

---

## Cost Optimization

| Resource | Development | Production |
|----------|-------------|------------|
| EC2 Instance | t3.micro ($8/mo) | t3.medium ($30/mo) |
| RDS PostgreSQL | - | db.t3.micro ($15/mo) |
| EBS Storage | 20 GB ($2/mo) | 50 GB ($5/mo) |
| Data Transfer | Minimal | ~$9/100GB |

**Tip:** Use Reserved Instances or Savings Plans for 30-60% discount on production workloads.
