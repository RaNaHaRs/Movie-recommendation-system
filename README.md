# 🎬 Movie Recommendation System

A content-based Movie Recommendation System that suggests similar movies based on user input.  
The system uses **Machine Learning (TF-IDF & Cosine Similarity)** for recommendations and integrates a **Python ML model with Spring Boot APIs and a web frontend**.

---

## 🚀 Features

- 🎥 Movie recommendation based on content similarity
- 🔍 Search movies by title
- 🤖 Machine Learning powered recommendations
- 🔗 Python Flask API integration
- ⚙️ Spring Boot backend services
- 🎨 Web-based frontend (Thymeleaf / HTML)
- 📊 Uses TMDB dataset
- ⚡ Fast similarity search

---

## 🛠️ Tech Stack

### Machine Learning
- Python
- Pandas
- Scikit-learn
- NumPy
- TF-IDF Vectorizer
- Cosine Similarity

### Backend
- Spring Boot
- Spring Web
- REST APIs

### Frontend
- Thymeleaf
- HTML
- CSS
- Bootstrap

### API Communication
- Flask (Python API)
- REST Integration

---

## 📂 Project Structure
# Movie-recommendation-system
┣ ml-model
┃ ┣ app.py
┃ ┣ model.pkl
┃ ┣ similarity.pkl
┃ ┗ dataset.csv
┣ spring-boot-app
┃ ┣ controller
┃ ┣ service
┃ ┣ config
┃ ┗ rest-client
┣ templates
┣ static
┗ pom.xml

---

## ⚙️ How It Works
1. Dataset is processed using TF-IDF
2. Cosine similarity matrix is created
3. Model saved using Pickle
4. Flask API loads model
5. Spring Boot calls Flask API
6. Results displayed on UI
---
## ⚙️ Setup & Installation
### 1️⃣ Clone Repository
```bash
git clone https://github.com/RaNaHaRs/Movie-recommendation-system.git
dd Movie-recommendation-system```
### 2️⃣ Run Python ML API 
```bash
dd ml-model 
pip install -r requirements.txt 
python app.py```
Flask server runs on:
http://localhost:5000 
### 3️⃣ Run Spring Boot Application 
```bash
mvn spring-boot:run```
Spring Boot runs on:
http://localhost:8080 
🔗 API Flow Frontend → Spring Boot → Flask API → ML Model → Flask → Spring Boot → UI 
📊 Dataset TMDB 5000 Movie Dataset Movies metadata Cast Crew Keywords Genres 
🧠 Recommendation Algorithm Content-Based Filtering TF-IDF Vectorization Cosine Similarity Top-N Similar Movies 
📸 Screenshots /screenshots/home.png /screenshots/search.png /screenshots/recommendations.png 
🧠 Future Improvements User login system Save favorite movies Hybrid recommendation system Collaborative filtering Movie posters using TMDB API Deploy ML model to cloud Docker support 
👨‍💻 Author Harsh Rana GitHub: https://github.com/RaNaHaRs 
📄 License This project is for educational purposes.
---
This one is **perfect for ML + Spring Boot combo project**
