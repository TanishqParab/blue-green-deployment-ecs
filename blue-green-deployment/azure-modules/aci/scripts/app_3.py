from flask import Flask, jsonify, render_template_string, request, redirect, url_for
import datetime
import uuid

app = Flask(__name__)
app_prefix = "/app3"  # Path prefix for all routes

# In-memory storage for blog posts
blog_posts = [
    {
        "id": "1",
        "title": "Welcome to Blue-Green Deployment Blog",
        "content": "This is a demonstration of a blog application running with blue-green deployment on AWS ECS.",
        "author": "Admin",
        "date": "2023-06-15"
    },
    {
        "id": "2",
        "title": "Benefits of Blue-Green Deployment",
        "content": "Blue-green deployment is a technique that reduces downtime and risk by running two identical production environments called Blue and Green.",
        "author": "DevOps Engineer",
        "date": "2023-06-16"
    }
]

# HTML template for the blog application
BLOG_TEMPLATE = '''
<!DOCTYPE html>
<html>
    <head>
        <title>Tech Blogs</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            body {
                font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                line-height: 1.6;
                color: #333;
                margin: 0;
                padding: 0;
                background-color: #f8f9fa;
            }
            .container {
                max-width: 1000px;
                margin: 0 auto;
                padding: 20px;
            }
            header {
                background-color: #007bff;
                color: white;
                padding: 1rem;
                text-align: center;
                margin-bottom: 2rem;
                box-shadow: 0 2px 5px rgba(0,0,0,0.1);
            }
            .version-badge {
                position: absolute;
                top: 10px;
                right: 10px;
                background-color: #28a745;
                color: white;
                padding: 5px 10px;
                border-radius: 20px;
                font-weight: bold;
            }
            .blog-post {
                background-color: white;
                border-radius: 8px;
                padding: 20px;
                margin-bottom: 20px;
                box-shadow: 0 2px 5px rgba(0,0,0,0.1);
            }
            .blog-title {
                color: #007bff;
                margin-top: 0;
            }
            .blog-meta {
                color: #6c757d;
                font-size: 0.9rem;
                margin-bottom: 15px;
            }
            .btn {
                display: inline-block;
                background-color: #007bff;
                color: white;
                padding: 8px 16px;
                text-decoration: none;
                border-radius: 4px;
                transition: background-color 0.3s;
                margin-right: 5px;
            }
            .btn:hover {
                background-color: #0069d9;
            }
            .btn-success {
                background-color: #28a745;
            }
            .btn-success:hover {
                background-color: #218838;
            }
            .btn-danger {
                background-color: #dc3545;
            }
            .btn-danger:hover {
                background-color: #c82333;
            }
            form {
                background-color: white;
                padding: 20px;
                border-radius: 8px;
                margin-bottom: 20px;
                box-shadow: 0 2px 5px rgba(0,0,0,0.1);
            }
            .form-group {
                margin-bottom: 15px;
            }
            label {
                display: block;
                margin-bottom: 5px;
                font-weight: bold;
            }
            input[type="text"], textarea {
                width: 100%;
                padding: 8px;
                border: 1px solid #ddd;
                border-radius: 4px;
                box-sizing: border-box;
            }
            textarea {
                min-height: 150px;
            }
            .search-form {
                display: flex;
                margin-bottom: 20px;
            }
            .search-form input {
                flex-grow: 1;
                margin-right: 10px;
            }
            .comment-section {
                margin-top: 20px;
                border-top: 1px solid #eee;
                padding-top: 15px;
            }
            .comment {
                background-color: #f8f9fa;
                padding: 10px;
                border-radius: 4px;
                margin-bottom: 10px;
            }
            .comment-meta {
                font-size: 0.8rem;
                color: #6c757d;
            }
            .nav-tabs {
                display: flex;
                list-style: none;
                padding: 0;
                margin: 0 0 20px 0;
                border-bottom: 1px solid #dee2e6;
            }
            .nav-tabs li {
                margin-right: 5px;
            }
            .nav-tabs a {
                display: block;
                padding: 8px 16px;
                text-decoration: none;
                color: #007bff;
                border-radius: 4px 4px 0 0;
            }
            .nav-tabs a.active {
                background-color: #007bff;
                color: white;
            }
        </style>
    </head>
    <body>
        <header>
            <h1>Tech Blogs</h1>
            <p>A demonstration of blue-green deployment on AWS ECS</p>
        </header>
        <div class="container">
            <ul class="nav-tabs">
                <li><a href="{{ app_prefix }}/" class="{{ 'active' if not form_visible and not view_post and not search_query else '' }}">All Posts</a></li>
                <li><a href="{{ app_prefix }}/new_post" class="{{ 'active' if form_visible else '' }}">New Post</a></li>
            </ul>
            
            {% if search_query %}
                <h2>Search Results for: "{{ search_query }}"</h2>
                <a href="{{ app_prefix }}/" class="btn">Back to All Posts</a>
            {% endif %}
            
            {% if not form_visible and not view_post %}
                <form class="search-form" action="{{ app_prefix }}/search" method="get">
                    <input type="text" name="q" placeholder="Search posts..." value="{{ search_query or '' }}">
                    <button type="submit" class="btn">Search</button>
                </form>
            {% endif %}
            
            {% if form_visible %}
                <form method="post" action="{{ app_prefix + '/edit_post/' + post.id if post else app_prefix + '/create_post' }}">
                    <h2>{{ 'Edit' if post else 'Create New' }} Post</h2>
                    <div class="form-group">
                        <label for="title">Title:</label>
                        <input type="text" id="title" name="title" value="{{ post.title if post else '' }}" required>
                    </div>
                    <div class="form-group">
                        <label for="author">Author:</label>
                        <input type="text" id="author" name="author" value="{{ post.author if post else '' }}" required>
                    </div>
                    <div class="form-group">
                        <label for="content">Content:</label>
                        <textarea id="content" name="content" required>{{ post.content if post else '' }}</textarea>
                    </div>
                    <button type="submit" class="btn btn-success">{{ 'Update' if post else 'Publish' }} Post</button>
                    <a href="{{ app_prefix + '/post/' + post.id if post else app_prefix + '/' }}" class="btn">Cancel</a>
                </form>
            {% elif view_post %}
                <div class="blog-post">
                    <h2 class="blog-title">{{ post.title }}</h2>
                    <div class="blog-meta">
                        Posted by {{ post.author }} on {{ post.date }}
                    </div>
                    <p>{{ post.content }}</p>
                    <div>
                        <a href="{{ app_prefix }}/edit_post/{{ post.id }}" class="btn">Edit</a>
                        <a href="{{ app_prefix }}/delete_post/{{ post.id }}" class="btn btn-danger" onclick="return confirm('Are you sure you want to delete this post?')">Delete</a>
                        <a href="{{ app_prefix }}/" class="btn">Back to All Posts</a>
                    </div>
                    
                    <div class="comment-section">
                        <h3>Comments ({{ post.comments|length if post.comments else 0 }})</h3>
                        
                        {% if post.comments %}
                            {% for comment in post.comments %}
                                <div class="comment">
                                    <p>{{ comment.content }}</p>
                                    <div class="comment-meta">
                                        By {{ comment.author }} on {{ comment.date }}
                                    </div>
                                </div>
                            {% endfor %}
                        {% else %}
                            <p>No comments yet.</p>
                        {% endif %}
                        
                        <form method="post" action="{{ app_prefix }}/add_comment/{{ post.id }}">
                            <h4>Add a Comment</h4>
                            <div class="form-group">
                                <label for="comment_author">Name:</label>
                                <input type="text" id="comment_author" name="author" required>
                            </div>
                            <div class="form-group">
                                <label for="comment_content">Comment:</label>
                                <textarea id="comment_content" name="content" required rows="3"></textarea>
                            </div>
                            <button type="submit" class="btn">Submit Comment</button>
                        </form>
                    </div>
                </div>
            {% else %}
                <div style="margin-bottom: 20px;">
                    <a href="{{ app_prefix }}/new_post" class="btn btn-success">Create New Post</a>
                </div>
                
                {% if posts %}
                    {% for post in posts %}
                        <div class="blog-post">
                            <h2 class="blog-title">{{ post.title }}</h2>
                            <div class="blog-meta">
                                Posted by {{ post.author }} on {{ post.date }}
                                {% if post.comments %}
                                    | {{ post.comments|length }} comment{{ 's' if post.comments|length != 1 else '' }}
                                {% endif %}
                            </div>
                            <p>{{ post.content[:200] + '...' if post.content|length > 200 else post.content }}</p>
                            <a href="{{ app_prefix }}/post/{{ post.id }}" class="btn">Read More</a>
                        </div>
                    {% endfor %}
                {% else %}
                    <div class="blog-post">
                        <p>No posts found.</p>
                    </div>
                {% endif %}
            {% endif %}
        </div>
    </body>
</html>
'''

@app.route('/')
@app.route('/app3')
@app.route('/app3/')
def home():
    return render_template_string(BLOG_TEMPLATE, posts=blog_posts, form_visible=False, view_post=False, search_query=None, app_prefix=app_prefix)

@app.route('/app3/new_post')
def new_post():
    return render_template_string(BLOG_TEMPLATE, posts=[], form_visible=True, post=None, view_post=False, search_query=None, app_prefix=app_prefix)

@app.route('/app3/create_post', methods=['POST'])
def create_post():
    if request.method == 'POST':
        new_post = {
            "id": str(uuid.uuid4())[:8],
            "title": request.form.get('title'),
            "content": request.form.get('content'),
            "author": request.form.get('author'),
            "date": datetime.datetime.now().strftime("%Y-%m-%d"),
            "comments": []
        }
        blog_posts.insert(0, new_post)  # Add to the beginning of the list
    return redirect(app_prefix + '/')

@app.route('/app3/post/<post_id>')
def view_post(post_id):
    post = next((p for p in blog_posts if p["id"] == post_id), None)
    if post:
        return render_template_string(BLOG_TEMPLATE, post=post, view_post=True, form_visible=False, search_query=None, app_prefix=app_prefix)
    return redirect(app_prefix + '/')

@app.route('/app3/edit_post/<post_id>')
def edit_post_form(post_id):
    post = next((p for p in blog_posts if p["id"] == post_id), None)
    if post:
        return render_template_string(BLOG_TEMPLATE, post=post, form_visible=True, view_post=False, search_query=None, app_prefix=app_prefix)
    return redirect(app_prefix + '/')

@app.route('/app3/edit_post/<post_id>', methods=['POST'])
def edit_post(post_id):
    post = next((p for p in blog_posts if p["id"] == post_id), None)
    if post and request.method == 'POST':
        post["title"] = request.form.get('title')
        post["content"] = request.form.get('content')
        post["author"] = request.form.get('author')
    return redirect(app_prefix + '/post/' + post_id)

@app.route('/app3/delete_post/<post_id>')
def delete_post(post_id):
    global blog_posts
    blog_posts = [p for p in blog_posts if p["id"] != post_id]
    return redirect(app_prefix + '/')

@app.route('/app3/add_comment/<post_id>', methods=['POST'])
def add_comment(post_id):
    post = next((p for p in blog_posts if p["id"] == post_id), None)
    if post and request.method == 'POST':
        if "comments" not in post:
            post["comments"] = []
        
        comment = {
            "id": str(uuid.uuid4())[:8],
            "content": request.form.get('content'),
            "author": request.form.get('author'),
            "date": datetime.datetime.now().strftime("%Y-%m-%d")
        }
        post["comments"].append(comment)
    return redirect(app_prefix + '/post/' + post_id)

@app.route('/app3/search')
def search():
    query = request.args.get('q', '').lower()
    if query:
        results = [p for p in blog_posts if 
                  query in p["title"].lower() or 
                  query in p["content"].lower() or 
                  query in p["author"].lower()]
        return render_template_string(BLOG_TEMPLATE, posts=results, form_visible=False, 
                                     view_post=False, search_query=query, app_prefix=app_prefix)
    return redirect(app_prefix + '/')

@app.route('/health')
@app.route('/app3/health')
def health():
    """Health check endpoint required for blue-green deployment"""
    try:
        # Add any additional health checks here (database connections, etc.)
        return jsonify({
            "status": "healthy",
            "version": "V1",
            "service": "blue-green-app-3"
        }), 200
    except Exception as e:
        return jsonify({
            "status": "unhealthy",
            "error": str(e)
        }), 500

if __name__ == '__main__':
    # Change port to 80 to match the container_port in terraform.tfvars
    app.run(host='0.0.0.0', port=80)